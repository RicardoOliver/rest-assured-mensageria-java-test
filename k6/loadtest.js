import http from "k6/http";
import { check, sleep } from "k6";

const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";

export const options = {
  scenarios: {
    publish_messages: {
      executor: "ramping-arrival-rate",
      startRate: 10,
      timeUnit: "1s",
      preAllocatedVUs: 50,
      maxVUs: 200,
      stages: [
        { target: 50, duration: "1m" },
        { target: 200, duration: "2m" },
        { target: 0, duration: "30s" }
      ]
    }
  },
  thresholds: {
    http_req_failed: ["rate<0.01"],
    http_req_duration: ["p(95)<500"]
  }
};

function uuid() {
  return `${Date.now()}-${Math.random().toString(16).slice(2)}-${__VU}-${__ITER}`;
}

export default function () {
  const messageId = `k6-${uuid()}`;
  const payload = {
    type: "ORDER_CREATED",
    data: { orderId: messageId, value: 10 }
  };

  const body = JSON.stringify({ messageId, payload });

  const publish = http.post(`${BASE_URL}/messages`, body, {
    headers: { "Content-Type": "application/json" }
  });

  check(publish, {
    "POST /messages -> 202": (r) => r.status === 202
  });

  const maxPolls = 25;
  for (let i = 0; i < maxPolls; i++) {
    const status = http.get(`${BASE_URL}/messages/${encodeURIComponent(messageId)}`);
    if (status.status === 200) {
      check(status, {
        "persisted": (r) => r.json("status") === "PERSISTED"
      });
      break;
    }
    sleep(0.2);
  }
}

