package com.example.messaging;

import static org.hamcrest.Matchers.equalTo;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import com.example.messaging.messaging.RabbitTopologyConfig;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.output.OutputFrame;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AsyncMessagingIT {
    static Network network = Network.newNetwork();
    static final String RABBIT_APP_USER = "app";
    static final String RABBIT_APP_PASSWORD = "app";

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("app")
                    .withUsername("app")
                    .withPassword("app")
                    .withNetwork(network)
                    .withNetworkAliases("postgres")
                    .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(3)))
                    .withLogConsumer(frame -> printFrame("POSTGRES", frame));

    @Container
    static GenericContainer<?> rabbitmq =
            new GenericContainer<>(DockerImageName.parse("rabbitmq:3.13-management"))
                    .withEnv("RABBITMQ_DEFAULT_USER", RABBIT_APP_USER)
                    .withEnv("RABBITMQ_DEFAULT_PASS", RABBIT_APP_PASSWORD)
                    .withExposedPorts(5672, 15672)
                    .withNetwork(network)
                    .withNetworkAliases("rabbitmq")
                    .waitingFor(Wait.forHttp("/").forPort(15672).forStatusCode(200).withStartupTimeout(Duration.ofMinutes(3)))
                    .withLogConsumer(frame -> printFrame("RABBITMQ", frame));

    @Container
    static GenericContainer<?> api =
            new GenericContainer<>(
                    new ImageFromDockerfile("messaging-api-it")
                            .withFileFromPath(".", Paths.get(".")))
                    .withEnv("RABBIT_HOST", "rabbitmq")
                    .withEnv("RABBIT_PORT", "5672")
                    .withEnv("RABBIT_USER", RABBIT_APP_USER)
                    .withEnv("RABBIT_PASSWORD", RABBIT_APP_PASSWORD)
                    .withEnv("POSTGRES_HOST", "postgres")
                    .withEnv("POSTGRES_PORT", "5432")
                    .withEnv("POSTGRES_DB", "app")
                    .withEnv("POSTGRES_USER", "app")
                    .withEnv("POSTGRES_PASSWORD", "app")
                    .withEnv("JAVA_TOOL_OPTIONS", "-Xms128m -Xmx512m")
                    .withExposedPorts(8080)
                    .withNetwork(network)
                    .dependsOn(postgres, rabbitmq)
                    .withStartupAttempts(3)
                    .waitingFor(Wait.forHttp("/actuator/health").forStatusCode(200).withStartupTimeout(Duration.ofMinutes(5)))
                    .withLogConsumer(frame -> printFrame("API", frame));

    private String apiBaseUrl;
    private String rabbitManagementBaseUrl;
    private String jdbcUrl;
    private MgmtCredentials rabbitMgmtCredentials;

    private record MgmtCredentials(String user, String password) {}

    @BeforeEach
    void resetState() {
        apiBaseUrl = "http://%s:%d".formatted(api.getHost(), api.getMappedPort(8080));
        rabbitManagementBaseUrl = "http://%s:%d".formatted(rabbitmq.getHost(), rabbitmq.getMappedPort(15672));
        jdbcUrl = postgres.getJdbcUrl();
        rabbitMgmtCredentials = resolveRabbitMgmtCredentials();

        waitForApiHealthy();
        waitForRabbitReady();

        // Mantém os testes determinísticos: zera DLQ e tabela, para que asserções de contagem sejam confiáveis.
        purgeDlq();
        truncateMessagesTable();
    }

    @Test
    void caminhoFeliz_mensagemPostada_consumida_ePersistida() {
        String messageId = UUID.randomUUID().toString();

        // Regra de negócio (consumer): payload precisa ter o campo obrigatório "type".
        Map<String, Object> payload = Map.of(
                "type", "ORDER_CREATED",
                "data", Map.of("orderId", "123", "value", 10)
        );

        Map<String, Object> request = Map.of(
                "messageId", messageId,
                "payload", payload
        );

        RestAssured
                .given()
                .baseUri(apiBaseUrl)
                .contentType(ContentType.JSON)
                .body(request)
                .when()
                .post("/messages")
                .then()
                .statusCode(202);

        // A API apenas publica no broker (async). Persistência ocorre no consumer.
        // Awaitility trata o "eventually consistent": espera até a linha aparecer no banco.
        Awaitility.await()
                .atMost(Duration.ofSeconds(60))
                .pollInterval(Duration.ofMillis(250))
                .until(() -> countMessagesById(messageId) == 1);

        Assertions.assertEquals(0, dlqMessageCount());
    }

    @Test
    void erroDeNegocio_mensagemInvalida_rejeitada_eVaiParaDlq() {
        String messageId = UUID.randomUUID().toString();

        // Payload é JSON válido, mas viola regra de negócio (não tem "type").
        Map<String, Object> invalidPayload = Map.of(
                "data", Map.of("orderId", "123")
        );

        Map<String, Object> request = Map.of(
                "messageId", messageId,
                "payload", invalidPayload
        );

        RestAssured
                .given()
                .baseUri(apiBaseUrl)
                .contentType(ContentType.JSON)
                .body(request)
                .when()
                .post("/messages")
                .then()
                .statusCode(202);

        // Consumer deve rejeitar sem requeue; queue principal dead-letter para a DLQ.
        Awaitility.await()
                .atMost(Duration.ofMinutes(2))
                .pollInterval(Duration.ofMillis(250))
                .until(() -> dlqMessageCount() == 1);

        // Como a mensagem foi para DLQ, ela não deve ser persistida.
        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(250))
                .until(() -> countMessagesById(messageId) == 0);
    }

    @Test
    void idempotencia_mesmoMessageId_duasVezes_persisteApenasUma() {
        String messageId = "idempotency-" + UUID.randomUUID();

        Map<String, Object> payload = Map.of(
                "type", "ORDER_CREATED",
                "data", Map.of("orderId", "123", "value", 10)
        );

        Map<String, Object> request = Map.of(
                "messageId", messageId,
                "payload", payload
        );

        RestAssured
                .given()
                .baseUri(apiBaseUrl)
                .contentType(ContentType.JSON)
                .body(request)
                .when()
                .post("/messages")
                .then()
                .statusCode(202);

        // Reenvio com o mesmo messageId simula retries / duplicação de entrega típica em EDA.
        RestAssured
                .given()
                .baseUri(apiBaseUrl)
                .contentType(ContentType.JSON)
                .body(request)
                .when()
                .post("/messages")
                .then()
                .statusCode(202);

        // Idempotência é verificada no ponto de efeito (banco), não no "status" do POST.
        Awaitility.await()
                .atMost(Duration.ofSeconds(60))
                .pollInterval(Duration.ofMillis(250))
                .until(() -> countMessagesById(messageId) == 1);
    }

    private void truncateMessagesTable() {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, "app", "app");
             PreparedStatement statement = connection.prepareStatement("truncate table messages")) {
            statement.execute();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to truncate messages table", e);
        }
    }

    private int countMessagesById(String messageId) {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, "app", "app");
             PreparedStatement statement = connection.prepareStatement("select count(*) from messages where message_id = ?")) {
            statement.setString(1, messageId);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to query messages table", e);
        }
    }

    private void purgeDlq() {
        var response = RestAssured
                .given()
                .baseUri(rabbitManagementBaseUrl)
                .auth().preemptive().basic(rabbitMgmtCredentials.user(), rabbitMgmtCredentials.password())
                .when()
                .delete("/api/queues/%2F/" + RabbitTopologyConfig.DLQ_QUEUE + "/contents")
                .andReturn();

        int statusCode = response.getStatusCode();
        if (statusCode == 204 || statusCode == 404) {
            return;
        }
        throw new IllegalStateException(
                "Unexpected status when purging DLQ. status=" + statusCode + " body=" + response.getBody().asString()
        );
    }

    private int dlqMessageCount() {
        var response = RestAssured
                .given()
                .baseUri(rabbitManagementBaseUrl)
                .auth().preemptive().basic(rabbitMgmtCredentials.user(), rabbitMgmtCredentials.password())
                .when()
                .get("/api/queues/%2F/" + RabbitTopologyConfig.DLQ_QUEUE)
                .andReturn();

        int statusCode = response.getStatusCode();
        if (statusCode == 404) {
            return 0;
        }
        if (statusCode != 200) {
            throw new IllegalStateException(
                    "Unexpected status when reading DLQ. status=" + statusCode + " body=" + response.getBody().asString()
            );
        }

        return response.jsonPath().getInt("messages");
    }

    private static void printFrame(String serviceName, OutputFrame frame) {
        String utf8String = frame.getUtf8String();
        if (utf8String == null || utf8String.isBlank()) {
            return;
        }
        System.out.print(("[" + serviceName + "] ") + utf8String);
    }

    private void waitForRabbitReady() {
        Awaitility.await()
                .atMost(Duration.ofMinutes(2))
                .pollInterval(Duration.ofSeconds(1))
                .until(() -> {
                    try {
                        ensureRabbitTopologyDeclared();
                        ensureRabbitAppPermissions();
                        return true;
                    } catch (Exception e) {
                        return false;
                    }
                });

        AtomicInteger lastStatusMain = new AtomicInteger(-1);
        AtomicReference<String> lastBodyMain = new AtomicReference<>("");
        AtomicInteger lastStatusDlq = new AtomicInteger(-1);
        AtomicReference<String> lastBodyDlq = new AtomicReference<>("");
        AtomicInteger lastConsumers = new AtomicInteger(-1);

        try {
            Awaitility.await()
                    .atMost(Duration.ofMinutes(3))
                    .pollInterval(Duration.ofSeconds(1))
                    .until(() -> {
                        try {
                            String mainQueuePath = "/api/queues/%2F/" + encodePathSegment(RabbitTopologyConfig.EVENTS_QUEUE);
                            String dlqQueuePath = "/api/queues/%2F/" + encodePathSegment(RabbitTopologyConfig.DLQ_QUEUE);

                            var main = RestAssured
                                    .given()
                                    .baseUri(rabbitManagementBaseUrl)
                                    .auth().preemptive().basic(rabbitMgmtCredentials.user(), rabbitMgmtCredentials.password())
                                    .when()
                                    .get(mainQueuePath)
                                    .andReturn();
                            lastStatusMain.set(main.getStatusCode());
                            lastBodyMain.set(main.getBody() == null ? "" : main.getBody().asString());

                            var dlq = RestAssured
                                    .given()
                                    .baseUri(rabbitManagementBaseUrl)
                                    .auth().preemptive().basic(rabbitMgmtCredentials.user(), rabbitMgmtCredentials.password())
                                    .when()
                                    .get(dlqQueuePath)
                                    .andReturn();
                            lastStatusDlq.set(dlq.getStatusCode());
                            lastBodyDlq.set(dlq.getBody() == null ? "" : dlq.getBody().asString());

                            if (main.getStatusCode() != 200 || dlq.getStatusCode() != 200) {
                                return false;
                            }

                            Integer consumers = main.jsonPath().getInt("consumers");
                            lastConsumers.set(consumers == null ? -1 : consumers);
                            return consumers != null && consumers > 0;
                        } catch (Exception e) {
                            lastStatusMain.set(-1);
                            lastBodyMain.set(e.toString());
                            lastStatusDlq.set(-1);
                            lastBodyDlq.set(e.toString());
                            lastConsumers.set(-1);
                            return false;
                        }
                    });
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Rabbit topology not ready. mainStatus=" + lastStatusMain.get()
                            + " mainBody=" + lastBodyMain.get()
                            + " dlqStatus=" + lastStatusDlq.get()
                            + " dlqBody=" + lastBodyDlq.get()
                            + " consumers=" + lastConsumers.get(),
                    e
            );
        }
    }

    private void ensureRabbitTopologyDeclared() {
        putExchange(RabbitTopologyConfig.EVENTS_EXCHANGE);
        putExchange(RabbitTopologyConfig.DLX_EXCHANGE);
        putQueue(
                RabbitTopologyConfig.EVENTS_QUEUE,
                Map.of(
                        "x-dead-letter-exchange", RabbitTopologyConfig.DLX_EXCHANGE,
                        "x-dead-letter-routing-key", RabbitTopologyConfig.DLQ_ROUTING_KEY
                )
        );
        putQueue(RabbitTopologyConfig.DLQ_QUEUE, Map.of());
        postBindingExchangeToQueue(
                RabbitTopologyConfig.EVENTS_EXCHANGE,
                RabbitTopologyConfig.EVENTS_QUEUE,
                RabbitTopologyConfig.EVENTS_ROUTING_KEY
        );
        postBindingExchangeToQueue(
                RabbitTopologyConfig.DLX_EXCHANGE,
                RabbitTopologyConfig.DLQ_QUEUE,
                RabbitTopologyConfig.DLQ_ROUTING_KEY
        );
    }

    private void putExchange(String exchangeName) {
        String path = "/api/exchanges/%2F/" + encodePathSegment(exchangeName);
        var response = RestAssured
                .given()
                .baseUri(rabbitManagementBaseUrl)
                .auth().preemptive().basic(rabbitMgmtCredentials.user(), rabbitMgmtCredentials.password())
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "type", "direct",
                        "durable", true,
                        "auto_delete", false,
                        "internal", false,
                        "arguments", Map.of()
                ))
                .when()
                .put(path)
                .andReturn();

        int statusCode = response.getStatusCode();
        if (statusCode == 201 || statusCode == 204) {
            return;
        }
        throw new IllegalStateException("Failed to declare exchange. name=" + exchangeName + " status=" + statusCode + " body=" + response.getBody().asString());
    }

    private void putQueue(String queueName, Map<String, Object> arguments) {
        String path = "/api/queues/%2F/" + encodePathSegment(queueName);
        var response = RestAssured
                .given()
                .baseUri(rabbitManagementBaseUrl)
                .auth().preemptive().basic(rabbitMgmtCredentials.user(), rabbitMgmtCredentials.password())
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "durable", true,
                        "auto_delete", false,
                        "arguments", arguments
                ))
                .when()
                .put(path)
                .andReturn();

        int statusCode = response.getStatusCode();
        if (statusCode == 201 || statusCode == 204) {
            return;
        }
        throw new IllegalStateException("Failed to declare queue. name=" + queueName + " status=" + statusCode + " body=" + response.getBody().asString());
    }

    private void postBindingExchangeToQueue(String exchangeName, String queueName, String routingKey) {
        String path = "/api/bindings/%2F/e/" + encodePathSegment(exchangeName) + "/q/" + encodePathSegment(queueName);
        var response = RestAssured
                .given()
                .baseUri(rabbitManagementBaseUrl)
                .auth().preemptive().basic(rabbitMgmtCredentials.user(), rabbitMgmtCredentials.password())
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "routing_key", routingKey,
                        "arguments", Map.of()
                ))
                .when()
                .post(path)
                .andReturn();

        int statusCode = response.getStatusCode();
        if (statusCode == 201 || statusCode == 204) {
            return;
        }
        String body = response.getBody().asString();
        if (statusCode == 400 && body != null && body.toLowerCase().contains("exists")) {
            return;
        }
        throw new IllegalStateException(
                "Failed to declare binding. exchange=" + exchangeName + " queue=" + queueName + " status=" + statusCode + " body=" + body
        );
    }

    private static String encodePathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private MgmtCredentials resolveRabbitMgmtCredentials() {
        MgmtCredentials app = new MgmtCredentials(RABBIT_APP_USER, RABBIT_APP_PASSWORD);
        if (canCallWhoAmI(app)) {
            return app;
        }
        MgmtCredentials guest = new MgmtCredentials("guest", "guest");
        if (canCallWhoAmI(guest)) {
            return guest;
        }
        throw new IllegalStateException("Unable to authenticate to RabbitMQ Management API with app/app or guest/guest");
    }

    private boolean canCallWhoAmI(MgmtCredentials credentials) {
        try {
            var response = RestAssured
                    .given()
                    .baseUri(rabbitManagementBaseUrl)
                    .auth().preemptive().basic(credentials.user(), credentials.password())
                    .when()
                    .get("/api/whoami")
                    .andReturn();
            return response.getStatusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private void ensureRabbitAppPermissions() {
        String path = "/api/permissions/%2F/" + encodePathSegment(RABBIT_APP_USER);
        var response = RestAssured
                .given()
                .baseUri(rabbitManagementBaseUrl)
                .auth().preemptive().basic(rabbitMgmtCredentials.user(), rabbitMgmtCredentials.password())
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "configure", ".*",
                        "write", ".*",
                        "read", ".*"
                ))
                .when()
                .put(path)
                .andReturn();

        int statusCode = response.getStatusCode();
        if (statusCode == 201 || statusCode == 204) {
            return;
        }
        throw new IllegalStateException(
                "Failed to set permissions for user " + RABBIT_APP_USER + ". status=" + statusCode + " body=" + response.getBody().asString()
        );
    }

    private void waitForApiHealthy() {
        AtomicInteger lastStatus = new AtomicInteger(-1);
        AtomicReference<String> lastBody = new AtomicReference<>("");

        try {
            Awaitility.await()
                    .atMost(Duration.ofMinutes(3))
                    .pollInterval(Duration.ofSeconds(1))
                    .until(() -> {
                        try {
                            var response = RestAssured
                                    .given()
                                    .baseUri(apiBaseUrl)
                                    .when()
                                    .get("/actuator/health")
                                    .andReturn();
                            lastStatus.set(response.getStatusCode());
                            lastBody.set(response.getBody() == null ? "" : response.getBody().asString());
                            return response.getStatusCode() == 200;
                        } catch (Exception e) {
                            lastStatus.set(-1);
                            lastBody.set(e.toString());
                            return false;
                        }
                    });
        } catch (Exception e) {
            throw new IllegalStateException(
                    "API did not become healthy. status=" + lastStatus.get() + " body=" + lastBody.get(),
                    e
            );
        }
    }
}

