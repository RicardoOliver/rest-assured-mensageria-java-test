<div align="center">

<br/>

```
███████╗██████╗  █████╗     ████████╗███████╗███████╗████████╗███████╗
██╔════╝██╔══██╗██╔══██╗    ╚══██╔══╝██╔════╝██╔════╝╚══██╔══╝██╔════╝
█████╗  ██║  ██║███████║       ██║   █████╗  ███████╗   ██║   ███████╗
██╔══╝  ██║  ██║██╔══██║       ██║   ██╔══╝  ╚════██║   ██║   ╚════██║
███████╗██████╔╝██║  ██║       ██║   ███████╗███████║   ██║   ███████║
╚══════╝╚═════╝ ╚═╝  ╚═╝       ╚═╝   ╚══════╝╚══════╝   ╚═╝   ╚══════╝
```

### Arquitetura Orientada a Eventos · Framework de Testes de Integração

<br/>

[![Build Status](https://img.shields.io/github/actions/workflow/status/RicardoOliver/rest-assured-mensageria-java-test/ci.yml?branch=main&style=flat-square&label=CI&color=00C853)](https://github.com/RicardoOliver/rest-assured-mensageria-java-test/actions)
[![Cobertura](https://img.shields.io/badge/cobertura-≥80%25-00C853?style=flat-square)](https://github.com/RicardoOliver/rest-assured-mensageria-java-test/actions)
[![Java](https://img.shields.io/badge/Java-17-ED8B00?style=flat-square&logo=openjdk&logoColor=white)](https://www.oracle.com/java/)
[![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.x-6DB33F?style=flat-square&logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![RabbitMQ](https://img.shields.io/badge/RabbitMQ-3.x-FF6600?style=flat-square&logo=rabbitmq&logoColor=white)](https://www.rabbitmq.com/)
[![Testcontainers](https://img.shields.io/badge/Testcontainers-1.x-2496ED?style=flat-square)](https://testcontainers.com/)
[![Licença](https://img.shields.io/badge/licença-MIT-blue?style=flat-square)](LICENSE)
[![Visualizações](https://komarev.com/ghpvc/?username=RicardoOliver-mensageria&color=00C853&style=flat-square&label=visualizações)](https://github.com/RicardoOliver)

<br/>

> **Framework de testes de integração de nível produção** para arquiteturas orientadas a eventos.  
> Valida o ciclo de vida assíncrono completo — da ingestão HTTP ao roteamento no broker, processamento pelo consumidor, garantia de idempotência e persistência final.

<br/>

[Início Rápido](#-início-rápido) · [Arquitetura](#-arquitetura) · [Estratégia de Testes](#-estratégia-de-testes) · [Observabilidade](#-observabilidade) · [Teste de Carga](#-teste-de-carga) · [Contribuindo](#-contribuindo)

</div>

---

## Visão Geral

Sistemas distribuídos modernos dependem de fluxos de mensagens assíncronos que são inerentemente mais difíceis de testar do que ciclos síncronos de requisição/resposta. Uma entrega falha, uma mensagem duplicada ou uma falha silenciosa no consumidor podem passar despercebidas até a produção.

Este framework elimina essa lacuna oferecendo uma suíte de testes de integração **determinística, reproduzível e hermética** — sem mocks, sem fakes, com infraestrutura real executando em containers.

```
┌──────────────┐  POST /messages  ┌──────────────────┐  events.msg  ┌──────────────┐
│   HTTP API   │ ───────────────▶ │    RabbitMQ       │ ───────────▶ │  Consumidor  │
│ Spring Boot  │  202 Accepted    │   events.x (DX)   │              │ (Validador)  │
└──────────────┘                  └──────────────────┘              └──────┬───────┘
                                          │ rejeitar / sem requeue          │ persistir
                                          ▼                                  ▼
                                  ┌──────────────┐                  ┌──────────────┐
                                  │  events.dlq   │                  │   Postgres   │
                                  │    (DLX)      │                  │   messages   │
                                  └──────────────┘                  └──────────────┘
```

---

## Arquitetura

### Componentes

| Componente | Responsabilidade | Tecnologia |
|------------|-----------------|-----------|
| **Camada de API** | Recebe requisições HTTP e publica no broker | Spring Boot 3, Rest Assured |
| **Message Broker** | Roteia mensagens; gerencia retentativas e dead-letter | RabbitMQ 3 |
| **Consumidor** | Valida regras de negócio e garante idempotência | Spring AMQP |
| **Persistência** | Armazena mensagens confirmadas com restrição de unicidade | PostgreSQL 15 |
| **DLQ** | Captura mensagens rejeitadas para inspeção e reprocessamento | RabbitMQ DLX/DLQ |

### Topologia RabbitMQ

```
events.x (direct exchange)
    │
    ├── routing key: events.msg ──▶ events.q (fila principal)
    │                                    │
    │                                    └─▶ Consumidor
    │                                             │
    │                                rejeitar (sem requeue)
    │                                             │
    └── DLX: events.dlx ◀────────────────────────┘
             │
             └──▶ events.dlq (dead-letter queue)
```

### Contrato de Idempotência

O consumidor garante o processamento **exatamente uma vez** via restrição `UNIQUE` no campo `message_id` a nível de banco de dados. Entregas duplicadas são silenciosamente descartadas — sem erro, sem roteamento para a DLQ.

---

## Stack

```
Linguagem           Java 17
Executor de Testes  JUnit 5
Cliente HTTP        Rest Assured
Asserções Async     Awaitility
Infra (ITs)         Testcontainers (Postgres · RabbitMQ · API)
Gate de Cobertura   JaCoCo (mínimo 80% de linhas/branches)
Gate de Segurança   Trivy (varredura de CVEs nas imagens de container)
Teste de Carga      k6
Observabilidade     Prometheus · Grafana
```

---

## Início Rápido

### Pré-requisitos

| Ferramenta | Versão Mínima |
|------------|--------------|
| Docker + Docker Compose | 24.x |
| Java | 17 |
| Maven | 3.9 |

### 1 — Clone o repositório

```bash
git clone https://github.com/RicardoOliver/rest-assured-mensageria-java-test.git
cd rest-assured-mensageria-java-test
```

### 2 — Suba o ambiente

```bash
docker compose up --build
```

> As portas são mapeadas dinamicamente para evitar conflitos. Descubra-as em tempo de execução:

```bash
docker compose port api      8080   # → Endpoint da API
docker compose port rabbitmq 15672  # → Management UI
docker compose port postgres 5432   # → Banco de dados
```

---

## Executando os Testes

O projeto utiliza perfis Maven para separar as camadas de teste e impor quality gates de forma isolada.

### Testes Unitários

Rápidos, sem Docker. Execute a cada iteração local.

```bash
mvn -B -ntp test
```

### Testes de Integração

Fluxo completo de ponta a ponta. O Testcontainers sobe toda a infraestrutura automaticamente.

```bash
mvn -B -ntp verify -Pit
```

### Gate de Cobertura

O JaCoCo exige ≥ 80% de cobertura de linhas e branches. Projetado para rodar junto com os testes de integração, que exercitam o fluxo assíncrono.

```bash
# Testes de integração + gate de cobertura (recomendado para paridade com CI)
mvn -B -ntp verify -Pci,it
```

> ⚠️ Executar `-Pci` isolado (sem `-Pit`) falhará com 0% de cobertura — este é o comportamento esperado. Os testes de integração são necessários para atingir o threshold do gate.

---

## Referência de API

| Método | Endpoint | Status | Descrição |
|--------|----------|--------|-----------|
| `POST` | `/messages` | `202 Accepted` | Publica mensagem no broker |
| `GET` | `/messages/{messageId}` | `200 OK` / `404` | Consulta estado de persistência |
| `GET` | `/actuator/health` | `200 OK` | Probe de liveness / readiness |
| `GET` | `/actuator/prometheus` | `200 OK` | Endpoint de scraping do Prometheus |

### POST /messages — Corpo da Requisição

```json
{
  "messageId": "550e8400-e29b-41d4-a716-446655440000",
  "payload": "conteúdo da sua mensagem aqui"
}
```

### Máquina de Estados Assíncrona

```
POST /messages
      │
      ▼
  202 Accepted ◀─── publicação no broker confirmada
      │
      ▼  (assíncrono)
  Consumidor processa
      │
      ├── válida + não duplicada ──▶ persistida ──▶ GET retorna 200
      │
      └── inválida / violação de regra de negócio ──▶ DLQ ──▶ GET retorna 404
```

---

## Estratégia de Testes

### Por que Awaitility?

Sistemas assíncronos não têm um delay de propagação fixo. `Thread.sleep()` gera testes frágeis que esperam tempo demais ou de menos. O Awaitility realiza polling com intervalos e timeouts configuráveis, assertando *quando* o estado está pronto — e não *após* um delay arbitrário.

```java
await()
    .atMost(10, SECONDS)
    .pollInterval(500, MILLISECONDS)
    .untilAsserted(() ->
        given()
            .get("/messages/{id}", messageId)
        .then()
            .statusCode(200)
    );
```

### Cenários de Teste

| Cenário | Resultado Esperado |
|---------|-------------------|
| Mensagem válida, primeira entrega | Persistida → `GET 200` |
| `messageId` duplicado | Descartada silenciosamente, idempotente → `GET 200` (original) |
| Payload inválido (regra de negócio) | Roteada para DLQ → `GET 404` |
| Consumidor indisponível e depois recuperado | Mensagem enfileirada, processada na recuperação → `GET 200` |
| Broker inacessível no momento da publicação | API retorna `5xx`, nenhum estado parcial |

Documentação completa do teste de mesa: [`docs/teste-de-mesa.md`](docs/teste-de-mesa.md)

---

## Observabilidade

### Subindo a Stack de Monitoramento

```bash
docker compose \
  -f docker-compose.yml \
  -f docker-compose.monitoring.yml \
  up --build
```

| Serviço | URL | Credenciais |
|---------|-----|-------------|
| Grafana | http://localhost:3000 | `admin` / `admin` |
| Prometheus | http://localhost:9090 | — |
| RabbitMQ Management | `docker compose port rabbitmq 15672` | `guest` / `guest` |

### Dashboard Provisionado: EDA Overview

O dashboard `EDA Overview` é entregue pronto para uso com:

- **RPS da API** — requisições por segundo via métricas do Spring Boot Actuator
- **Profundidade das Filas** — contagem de mensagens em `events.q` e `events.dlq` via RabbitMQ Exporter
- **Commits/s no Postgres** — throughput de escrita via Postgres Exporter
- **Lag do Consumidor** — tempo entre publicação e persistência via métricas da aplicação

Referência de queries: [`docs/prometheus-queries.md`](docs/prometheus-queries.md)

---

## Teste de Carga

O script k6 implementa o padrão **publicar + consultar**: publica uma mensagem e realiza polling em `GET /messages/{messageId}` até a persistência ser confirmada, medindo a latência de ponta a ponta.

```bash
k6 run \
  -e BASE_URL=http://localhost:8080 \
  k6/loadtest.js
```

### Métricas Coletadas

| Métrica | Descrição |
|---------|-----------|
| `http_req_duration` | Tempo de resposta da API (p50, p95, p99) |
| `e2e_latency` | Tempo total entre POST e GET 200 confirmado |
| `message_loss_rate` | % de mensagens publicadas e nunca persistidas |
| `dlq_rate` | % de mensagens roteadas para a dead-letter queue |

---

## Pipeline de CI/CD

```
push / PR
    │
    ├── Testes Unitários ─────────────────────── feedback rápido (< 30s)
    │
    ├── Testes de Integração (Testcontainers) ── hermético, sem dependências externas
    │
    ├── Gate de Cobertura (JaCoCo ≥ 80%) ──────── obrigatório, bloqueia merge
    │
    └── Gate de Segurança (Trivy) ──────────────── varredura de CVEs nas imagens
```

Os relatórios são publicados em:
- **GitHub Artifacts** — relatório HTML do JaCoCo por execução (Actions → run → Artifacts)
- **GitHub Pages** — link fixo em Settings → Pages (source: workflow `pages`)

---

## Estrutura do Projeto

```
.
├── src/
│   ├── main/java/           # Aplicação Spring Boot (API + Consumidor)
│   └── test/java/
│       ├── unit/            # Testes unitários (sem containers)
│       └── integration/     # Testes de integração (Testcontainers)
├── k6/
│   └── loadtest.js          # Script de teste de carga k6
├── docs/
│   ├── teste-de-mesa.md     # Documentação do fluxo assíncrono
│   ├── docker-wsl-grafana-prometheus.md
│   └── prometheus-queries.md
├── docker-compose.yml                # Serviços principais
├── docker-compose.monitoring.yml     # Overlay Prometheus + Grafana
└── pom.xml                           # Build Maven (perfis: it, ci)
```

---

## Documentação

| Documento | Descrição |
|-----------|-----------|
| [`docs/teste-de-mesa.md`](docs/teste-de-mesa.md) | Estados do fluxo assíncrono, regras de transição e pontos de verificação |
| [`docs/docker-wsl-grafana-prometheus.md`](docs/docker-wsl-grafana-prometheus.md) | Configuração de WSL + Docker para a stack de monitoramento |
| [`docs/prometheus-queries.md`](docs/prometheus-queries.md) | Referência PromQL para métricas de EDA |

---

## Contribuindo

Contribuições são bem-vindas. Siga as diretrizes abaixo para manter a consistência do projeto.

### Fluxo de Desenvolvimento

```bash
# 1. Faça fork e clone
git clone https://github.com/SEU_FORK/rest-assured-mensageria-java-test.git

# 2. Crie uma branch de feature
git checkout -b feat/nome-da-sua-feature

# 3. Execute a suíte completa antes de abrir o PR
mvn -B -ntp verify -Pci,it

# 4. Abra um pull request contra a branch main
```

### Convenção de Commits

Este projeto segue o padrão [Conventional Commits](https://www.conventionalcommits.org/pt-br/):

```
feat(consumidor): adiciona backoff de retry para falhas transitórias
fix(api): retorna 400 quando messageId está ausente
test(it): adiciona asserção de roteamento para DLQ com payload inválido
docs: atualiza prometheus-queries.md com métrica de lag
```

### Checklist de Pull Request

- [ ] Testes unitários passando (`mvn test`)
- [ ] Testes de integração passando (`mvn verify -Pit`)
- [ ] Gate de cobertura passando (`mvn verify -Pci,it`)
- [ ] Novos cenários documentados em `docs/teste-de-mesa.md` quando aplicável
- [ ] Nenhum novo CVE introduzido (gate do Trivy)

---

## Projetos Relacionados

- **[rest-assured-mensageria-java-test](https://github.com/RicardoOliver/rest-assured-mensageria-java-test)** — Testes de API e integração com foco em sistemas de mensageria

---

<div align="center">

<br/>

Desenvolvido com precisão por **[Ricardo Oliver](https://github.com/RicardoOliver)**

<br/>

[![GitHub Stats](https://github-readme-stats.vercel.app/api?username=RicardoOliver&show_icons=true&theme=github_dark&include_all_commits=true&count_private=true&hide_border=true)](https://github.com/RicardoOliver)
[![Linguagens](https://github-readme-stats.vercel.app/api/top-langs/?username=RicardoOliver&layout=compact&langs_count=7&theme=github_dark&hide_border=true)](https://github.com/RicardoOliver)

<br/>

![Visualizações](https://komarev.com/ghpvc/?username=RicardoOliver-mensageria&color=00C853&style=flat-square&label=VISUALIZAÇÕES+DO+PROJETO)

<br/>

*Se este projeto te ajudou, considera deixar uma ⭐*

</div>
