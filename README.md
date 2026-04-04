# Framework de Testes Assíncronos (API → Broker → Persistência)

Este repositório demonstra um framework de testes de integração para arquitetura orientada a eventos (EDA), validando o fluxo assíncrono de ponta a ponta:

`POST /messages` → publicação no RabbitMQ → consumo assíncrono → persistência no Postgres → (ou DLQ em caso de erro).

## Arquitetura (visão rápida)

- **API (Spring Boot)** recebe a requisição e publica uma mensagem no broker (resposta `202 Accepted`).
- **RabbitMQ** roteia a mensagem para a fila principal (`events.q`). Em caso de rejeição sem requeue, roteia para DLQ (`events.dlq`) via DLX.
- **Consumer** lê de `events.q`, aplica validação de negócio, garante idempotência por `messageId` e persiste no banco.
- **Postgres** guarda o efeito final (tabela `messages` com `message_id` único).

Topologia RabbitMQ:
- Exchange principal: `events.x` (direct)
- Routing key principal: `events.msg`
- Fila principal: `events.q`
- DLX: `events.dlx`
- DLQ: `events.dlq`

## Stack

- Java 17
- JUnit 5
- Rest Assured
- Awaitility
- Testcontainers (Postgres + RabbitMQ + API em containers durante os ITs)
- JaCoCo (coverage gate em CI)
- Trivy (vulnerability gate em CI)

## Endpoints

- `POST /messages` (publica no broker e retorna `202`)
- `GET /messages/{messageId}` (retorna `200` quando persistido, `404` caso ainda não tenha sido persistido)
- `GET /actuator/health`
- `GET /actuator/prometheus` (métricas para Prometheus)

## Como rodar localmente (desenvolvimento)

Pré-requisitos:
- Docker + Docker Compose
- Java 17 + Maven

### Subir apenas API + Broker + Banco (manual)

```bash
docker compose up --build
```

Como as portas estão mapeadas dinamicamente (para evitar conflito), descubra as portas expostas:

```bash
docker compose port api 8080
docker compose port rabbitmq 15672
docker compose port postgres 5432
```

### Rodar testes

Unitários (rápidos, não exigem Docker):

```bash
mvn -B -ntp test
```

Integração (ponta-a-ponta; exige Docker e sobe o ambiente via Testcontainers):

```bash
mvn -B -ntp verify -Pit
```

Quality gate de coverage (como no CI):

```bash
mvn -B -ntp verify -Pci
```

Observação: o gate de coverage foi pensado para rodar junto com os testes de integração (que exercitam o fluxo assíncrono). Se você executar apenas `-Pci` sem `-Pit`/`-Pci,it`, é esperado falhar por cobertura 0%.

CI completo (IT + gate de coverage):

```bash
mvn -B -ntp verify -Pci,it
```

## Observabilidade (Prometheus + Grafana)

Suba o ambiente com monitoramento:

```bash
docker compose -f docker-compose.yml -f docker-compose.monitoring.yml up --build
```

Acessos:
- Grafana: `http://localhost:3000` (admin/admin)
- Prometheus: `http://localhost:9090`

Dashboard provisionado:
- `EDA Overview` (RPS da API, profundidade de filas no RabbitMQ via exporter, commits/s no Postgres via exporter)

## Teste de carga (k6)

O script publica mensagens e faz polling no endpoint `GET /messages/{messageId}` até detectar persistência.

```bash
k6 run -e BASE_URL=http://localhost:8080 k6/loadtest.js
```

## Documentação do teste de mesa

Detalhamento técnico do fluxo assíncrono, estados esperados e pontos de verificação:
- [docs/teste-de-mesa.md](docs/teste-de-mesa.md)
- [docs/docker-wsl-grafana-prometheus.md](docs/docker-wsl-grafana-prometheus.md)
- [docs/prometheus-queries.md](docs/prometheus-queries.md)

## Relatórios no GitHub

- Artifacts (por execução): aba Actions → run → Artifacts
- Pages (link fixo): Settings → Pages → Source = GitHub Actions, e acessar a URL publicada pelo workflow "pages"

## 📊 Estatísticas e Visibilidade

Aqui você pode agrupar os seus contadores e badges para ficarem centralizados e limpos:

<p align="center">
<img src="https://komarev.com/ghpvc/?username=RicardoOliver&color=ff69b4&style=for-the-badge&label=VISITANTES" alt="Contador de visitantes"/>
</p>

<p align="center">
<img src="https://github-readme-stats.vercel.app/api?username=RicardoOliver&show_icons=true&theme=dracula&include_all_commits=true&count_private=true" alt="GitHub Stats" height="180em"/>
<img src="https://github-readme-stats.vercel.app/api/top-langs/?username=RicardoOliver&layout=compact&langs_count=7&theme=dracula" alt="Top Languages" height="180em"/>
</p>
