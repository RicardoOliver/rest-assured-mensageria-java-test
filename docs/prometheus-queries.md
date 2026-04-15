# Prometheus (PromQL) — Como consultar métricas

Este guia mostra como “usar” o Prometheus neste projeto: validar targets e executar queries (PromQL).

## 1) Abrir o Prometheus

- URL: `http://localhost:9090`

## 2) Validar se está tudo UP

Na UI do Prometheus:

- `Status` → `Targets`
- Verifique se os jobs estão `UP`

Se algum job estiver `DOWN`, abra os detalhes para ver o erro (endpoint errado, container fora do ar, etc).

## 3) Onde escrever as queries

Use:

- `Graph` (mais simples) ou
- `Explore` (melhor para debug)

Digite a query e clique em `Execute`.

## 4) Queries básicas (saúde)

Tudo que está sendo coletado:

```promql
up
```

Somente targets UP:

```promql
up == 1
```

Somente targets DOWN:

```promql
up == 0
```

## 5) API (Spring Boot / Micrometer)

CPU do processo:

```promql
process_cpu_usage
```

Memória JVM:

```promql
jvm_memory_used_bytes
```

Threads:

```promql
jvm_threads_live_threads
```

Requisições HTTP (se disponível na sua versão do Micrometer/Spring):

```promql
http_server_requests_seconds_count
```

Latência (p95) HTTP (se disponível):

```promql
histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket[5m])) by (le))
```

## 6) RabbitMQ (rabbitmq-exporter)

Mensagens prontas para consumo (por fila):

```promql
rabbitmq_queue_messages_ready
```

Mensagens totais (prontas + unacked):

```promql
rabbitmq_queue_messages
```

Conexões:

```promql
rabbitmq_connections
```

Dica: filtre pela fila principal/DLQ quando quiser focar no fluxo EDA:

```promql
rabbitmq_queue_messages_ready{queue=~"events\\.q|events\\.dlq"}
```

## 7) Postgres (postgres-exporter)

Exporter UP:

```promql
pg_up
```

Conexões ativas:

```promql
pg_stat_database_numbackends
```

Commits por segundo (aprox):

```promql
rate(pg_stat_database_xact_commit[5m])
```

## 8) Dicas rápidas

- Para ver o endpoint de métricas “cru” (sem Prometheus), normalmente é `GET /metrics` no exporter (ex.: `http://localhost:9419/metrics`), mas no compose as portas podem variar e/ou estar fixas.
- Se você não estiver vendo as métricas do Spring, confirme que o Actuator está expondo `prometheus` e que o Prometheus está com o scrape correto.
