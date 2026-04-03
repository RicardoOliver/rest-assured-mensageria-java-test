# Teste de Mesa (Dry Run) — Fluxo Assíncrono EDA

Objetivo: descrever, de forma técnica, o que acontece do `POST` na API até a persistência final (ou DLQ), com o estado esperado das variáveis, filas e banco.

## Contexto e contratos

### Contrato de entrada (HTTP)

- Endpoint: `POST /messages`
- Corpo:
  - `messageId`: identificador lógico da mensagem (chave de idempotência no efeito final)
  - `payload`: JSON com dados de negócio

Exemplo válido:

```json
{
  "messageId": "6b0f3b8b-3d7f-4d6f-a6f8-6cf1a9f7a2f2",
  "payload": {
    "type": "ORDER_CREATED",
    "data": { "orderId": "123", "value": 10 }
  }
}
```

Resposta:
- `202 Accepted` (a API aceitou e publicou, mas o processamento/persistência é assíncrono).

### Contrato de mensagem (AMQP)

Headers:
- `messageId`: string (obrigatório) — correlação e idempotência.
- `producedAt`: timestamp ISO (diagnóstico).

Body:
- JSON serializado do `payload`.

### Topologia do RabbitMQ

- Exchange principal: `events.x` (direct)
- Routing key principal: `events.msg`
- Fila principal: `events.q`
- Dead-letter exchange: `events.dlx`
- Dead-letter routing key: `events.msg.dlq`
- DLQ: `events.dlq`

Regra de DLQ:
- Se o consumer **rejeitar sem requeue**, a mensagem sai de `events.q` e é roteada para `events.dlq`.

### Persistência (Postgres)

- Tabela: `messages`
- Restrição: `message_id` é `UNIQUE`

Campos relevantes:
- `message_id` (idempotência)
- `payload` (corpo original)
- `created_at` (quando o efeito foi materializado no banco)

## Fluxo 1 — Caminho feliz (mensagem publicada, consumida e persistida)

### 1) POST na API

Estado inicial esperado:
- `events.q.messages_ready = 0`
- `events.dlq.messages_ready = 0`
- `DB.count(messages where message_id = X) = 0`

Entrada:
- `request.messageId = X`
- `request.payload = {"type":"ORDER_CREATED", ...}`

Saída HTTP:
- `202 Accepted`

O que “fica na cabeça” da API:
- A API não mantém estado do processamento. Ela apenas dispara o evento e devolve `202`.

### 2) Publicação no broker (Producer)

Variáveis importantes no producer:
- `headers["messageId"] = X`
- `headers["producedAt"] = t0`
- `body = payloadJson`

Efeito no broker:
- Mensagem é publicada em `exchange=events.x` com `routingKey=events.msg`
- Pela binding, entra em `queue=events.q`

Estado esperado após publicação:
- `events.q.messages_ready ≈ 1` (pode ser 0 se um consumer já tiver pegado imediatamente)

### 3) Consumo assíncrono (Consumer)

Ao consumir, o consumer monta o contexto de execução:
- `messageId = header("messageId")`
- `payload = body`

Validações:
- Validação estrutural: body deve ser JSON válido
- Validação de negócio: `payload.type` deve existir e ser não vazio

Idempotência (antes do efeito):
- `repo.findByMessageId(X)`:
  - Se **existe**: “já processei”, então retorna sem persistir (efeito idempotente)
  - Se **não existe**: segue para persistência

### 4) Persistência (efeito final)

Operação:
- `INSERT INTO messages(message_id, payload, created_at) VALUES (X, payload, t1)`

Estado final esperado:
- `DB.count(messages where message_id = X) = 1`
- `events.q.messages_ready = 0`
- `events.dlq.messages_ready = 0`

Ponto de observação:
- `tPersist = t1 - t0` representa “latência E2E de materialização”.

## Fluxo 2 — Erro de negócio (mensagem inválida vai para DLQ)

Exemplo: payload é JSON válido, mas sem o campo obrigatório `type`.

### 1) POST na API

Entrada:
- `request.messageId = Y`
- `request.payload = {"data": {...}}`

Saída HTTP:
- `202 Accepted` (o contrato da API continua sendo assíncrono; o erro é do consumidor)

### 2) Publicação no broker

Efeito:
- Mensagem entra em `events.q` normalmente.

### 3) Consumo + rejeição sem requeue

Variáveis:
- `messageId = Y`
- `payload.type = null`

Decisão do consumer:
- Como viola regra de negócio, o consumer rejeita a mensagem **sem requeue**.

Efeito no RabbitMQ (dead-letter):
- Mensagem sai de `events.q`
- RabbitMQ publica em `events.dlx` com `events.msg.dlq`
- Mensagem entra em `events.dlq`

Estado final esperado:
- `events.q.messages_ready = 0`
- `events.dlq.messages_ready = 1`
- `DB.count(messages where message_id = Y) = 0`

Por que isso é saudável em EDA:
- Evita loop infinito de retries quando o problema é “dado inválido”.
- Permite tratamento/triagem (replay/manual) com rastreabilidade.

## Fluxo 3 — Idempotência (mesmo messageId publicado mais de uma vez)

Motivação real:
- Duplicação por retry do produtor, timeout, redelivery do broker, ou reprocessamento.

Entrada:
- Duas chamadas `POST /messages` com o mesmo `messageId = Z`

Estado desejado:
- `DB.count(messages where message_id = Z) = 1` (efeito único)

Como é garantido:
- Camada 1 (lógica): consumer checa `repo.findByMessageId(Z)` antes de inserir.
- Camada 2 (banco): `UNIQUE(message_id)` protege contra corrida (dois consumers/processos).

Resultado esperado:
- Mesmo que duas mensagens cheguem ao consumer, o efeito final é único.

## Onde os testes automatizados “encostam”

O teste de integração não valida “apenas o POST”.
Ele valida o efeito observável do processamento assíncrono:

- **Caminho feliz**:
  - `POST /messages` → `202`
  - “eventualmente”: `DB.count(message_id) == 1`
  - “sempre”: `DLQ == 0`
- **Erro / DLQ**:
  - `POST /messages` → `202`
  - “eventualmente”: `DLQ == 1`
  - “sempre”: `DB.count(message_id) == 0`
- **Idempotência**:
  - 2x `POST /messages` com o mesmo `messageId` → `202`
  - “eventualmente”: `DB.count(message_id) == 1`

Arquivo de referência:
- `src/test/java/.../AsyncMessagingIT.java`

