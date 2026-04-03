package com.example.messaging;

import static org.hamcrest.Matchers.equalTo;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.DockerComposeContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.output.Slf4jLogConsumer;

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AsyncMessagingIT {
    // Sobe o ambiente real (API + RabbitMQ + Postgres) via docker-compose,
    // garantindo que o teste valide a integração assíncrona ponta-a-ponta.
    @Container
    static DockerComposeContainer<?> environment =
            new DockerComposeContainer<>(new File("docker-compose.yml"))
                    .withBuild(true)
                    .withExposedService("api", 8080, Wait.forHttp("/actuator/health").forStatusCode(200).withStartupTimeout(Duration.ofMinutes(3)))
                    .withExposedService("rabbitmq", 15672, Wait.forHttp("/").forStatusCode(200).withStartupTimeout(Duration.ofMinutes(3)))
                    .withExposedService("postgres", 5432, Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(3)))
                    .withLogConsumer("api", new Slf4jLogConsumer(LoggerFactory.getLogger("API-CONTAINER")))
                    .withLogConsumer("rabbitmq", new Slf4jLogConsumer(LoggerFactory.getLogger("RABBITMQ-CONTAINER")))
                    .withLogConsumer("postgres", new Slf4jLogConsumer(LoggerFactory.getLogger("POSTGRES-CONTAINER")));

    private String apiBaseUrl;
    private String rabbitManagementBaseUrl;
    private String jdbcUrl;

    @BeforeEach
    void resetState() {
        // Descobre os hosts/ports reais mapeados dinamicamente pelo Compose (ports "0:..."),
        // evitando conflito de porta em CI e rodando em paralelo com outros jobs.
        String apiHost = environment.getServiceHost("api", 8080);
        Integer apiPort = environment.getServicePort("api", 8080);
        apiBaseUrl = "http://%s:%d".formatted(apiHost, apiPort);

        String rabbitHost = environment.getServiceHost("rabbitmq", 15672);
        Integer rabbitPort = environment.getServicePort("rabbitmq", 15672);
        rabbitManagementBaseUrl = "http://%s:%d".formatted(rabbitHost, rabbitPort);

        String postgresHost = environment.getServiceHost("postgres", 5432);
        Integer postgresPort = environment.getServicePort("postgres", 5432);
        jdbcUrl = "jdbc:postgresql://%s:%d/app".formatted(postgresHost, postgresPort);

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

        // Checagem adicional: caminho feliz não deve produzir mensagens na DLQ.
        RestAssured
                .given()
                .baseUri(rabbitManagementBaseUrl)
                .auth().preemptive().basic("app", "app")
                .when()
                .get("/api/queues/%2F/events.dlq")
                .then()
                .statusCode(200)
                .body("messages", equalTo(0));
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
                .atMost(Duration.ofSeconds(60))
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
        // RabbitMQ Management API: limpa o conteúdo da fila para isolamento de teste.
        RestAssured
                .given()
                .baseUri(rabbitManagementBaseUrl)
                .auth().preemptive().basic("app", "app")
                .when()
                .delete("/api/queues/%2F/events.dlq/contents")
                .then()
                .statusCode(204);
    }

    private int dlqMessageCount() {
        return RestAssured
                .given()
                .baseUri(rabbitManagementBaseUrl)
                .auth().preemptive().basic("app", "app")
                .when()
                .get("/api/queues/%2F/events.dlq")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getInt("messages");
    }
}

