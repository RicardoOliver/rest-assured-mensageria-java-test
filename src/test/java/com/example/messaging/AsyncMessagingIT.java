package com.example.messaging;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import java.nio.file.Paths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jacoco.core.tools.ExecDumpClient;
import org.awaitility.Awaitility;
import static org.awaitility.pollinterval.FibonacciPollInterval.fibonacci;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import com.example.messaging.messaging.RabbitTopologyConfig;
import com.rabbitmq.client.ConnectionFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.output.OutputFrame;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import java.util.Comparator;

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AsyncMessagingIT {
    static Network network = Network.newNetwork();
    static final String RABBIT_APP_USER = "app";
    static final String RABBIT_APP_PASSWORD = "app";
    static final String DB_USER = "app";
    static final String DB_PASSWORD = "app";
    static final String API_IMAGE_ENV = "API_IMAGE";
    static final String DEFAULT_API_IMAGE = "messaging-api-it";
    static final String RUN_ID = "it-" + UUID.randomUUID();
    static final org.awaitility.pollinterval.PollInterval DEFAULT_POLL_INTERVAL = fibonacci(10, TimeUnit.MILLISECONDS);
    static final String JACOCO_VERSION = "0.8.12";
    static final Path JACOCO_DIR = Paths.get("target", "jacoco").toAbsolutePath();
    static final Path JACOCO_IT_EXEC = JACOCO_DIR.resolve("jacoco-it.exec");
    static final int JACOCO_TCP_PORT = 6300;

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("app")
                    .withUsername(DB_USER)
                    .withPassword(DB_PASSWORD)
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
    static GenericContainer<?> api = createApiContainer();

    private String apiBaseUrl;
    private String jdbcUrl;

    @BeforeEach
    void resetState() {
        apiBaseUrl = "http://%s:%d".formatted(api.getHost(), api.getMappedPort(8080));
        jdbcUrl = postgres.getJdbcUrl();

        waitForApiHealthy();
        waitForRabbitReady();

        purgeDlq();
    }

    @AfterAll
    void dumpJacocoFromApiContainer() {
        try {
            Files.createDirectories(JACOCO_DIR);

            int port = api.getMappedPort(JACOCO_TCP_PORT);
            ExecDumpClient client = new ExecDumpClient();
            client.setReset(false);
            client.setRetryCount(10);
            client.setRetryDelay(1000);

            var loader = client.dump(api.getHost(), port);
            Files.deleteIfExists(JACOCO_IT_EXEC);
            loader.save(JACOCO_IT_EXEC.toFile(), false);

            int coveredClasses = loader.getExecutionDataStore().getContents().size();
            long fileSize = Files.size(JACOCO_IT_EXEC);
            System.out.println("[JACOCO] dumped exec data: file=" + JACOCO_IT_EXEC + " size=" + fileSize + " classes=" + coveredClasses);

            if (coveredClasses == 0) {
                throw new IllegalStateException("JaCoCo dump returned 0 classes. file=" + JACOCO_IT_EXEC + " size=" + fileSize);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to dump JaCoCo exec data from API container", e);
        }
    }

    @Test
    void caminhoFeliz_mensagemPostada_consumida_ePersistida() {
        String messageId = newMessageId("happy");

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
        String messageId = newMessageId("invalid");

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
                .pollDelay(Duration.ofMillis(100))
                .pollInterval(DEFAULT_POLL_INTERVAL)
                .until(() -> dlqMessageCount() == 1);

        // Como a mensagem foi para DLQ, ela não deve ser persistida.
        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollDelay(Duration.ofMillis(100))
                .pollInterval(DEFAULT_POLL_INTERVAL)
                .until(() -> countMessagesById(messageId) == 0);
    }

    @Test
    void idempotencia_mesmoMessageId_duasVezes_persisteApenasUma() {
        String messageId = newMessageId("idempotency");

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
                .pollDelay(Duration.ofMillis(100))
                .pollInterval(DEFAULT_POLL_INTERVAL)
                .until(() -> countMessagesById(messageId) == 1);
                
    }

    private int countMessagesById(String messageId) {
        try (Connection connection = openConnection();
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

    private Connection openConnection() throws Exception {
        return DriverManager.getConnection(jdbcUrl, DB_USER, DB_PASSWORD);
    }

    private String newMessageId(String prefix) {
        return RUN_ID + "-" + prefix + "-" + UUID.randomUUID();
    }

    private void purgeDlq() {
        try (com.rabbitmq.client.Connection connection = openAmqpConnection();
             com.rabbitmq.client.Channel channel = connection.createChannel()) {
            if (!queueExists(channel, RabbitTopologyConfig.DLQ_QUEUE)) {
                return;
            }
            channel.queuePurge(RabbitTopologyConfig.DLQ_QUEUE);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to purge DLQ", e);
        }
    }

    private int dlqMessageCount() {
        try (com.rabbitmq.client.Connection connection = openAmqpConnection();
             com.rabbitmq.client.Channel channel = connection.createChannel()) {
            if (!queueExists(channel, RabbitTopologyConfig.DLQ_QUEUE)) {
                return 0;
            }
            return channel.queueDeclarePassive(RabbitTopologyConfig.DLQ_QUEUE).getMessageCount();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read DLQ message count", e);
        }
    }

    private static void printFrame(String serviceName, OutputFrame frame) {
        String utf8String = frame.getUtf8String();
        if (utf8String == null || utf8String.isBlank()) {
            return;
        }
        System.out.print(("[" + serviceName + "] ") + utf8String);
    }

    private static GenericContainer<?> createApiContainer() {
        String apiImage = System.getenv(API_IMAGE_ENV);
        GenericContainer<?> container;
        if (apiImage != null && !apiImage.isBlank()) {
            container = new GenericContainer<>(DockerImageName.parse(apiImage));
        } else {
            Path appJarPath = resolveLocalAppJar();
            container = new GenericContainer<>(DockerImageName.parse("eclipse-temurin:17-jre"))
                    .withWorkingDirectory("/app")
                    .withCopyFileToContainer(MountableFile.forHostPath(appJarPath), "/app/app.jar")
                    .withCommand("java", "-jar", "/app/app.jar");
        }

        Path agentJarPath = Paths.get(
                System.getProperty("user.home"),
                ".m2",
                "repository",
                "org",
                "jacoco",
                "org.jacoco.agent",
                JACOCO_VERSION,
                "org.jacoco.agent-" + JACOCO_VERSION + "-runtime.jar"
        );
        if (!Files.exists(agentJarPath)) {
            throw new IllegalStateException("JaCoCo agent jar not found at " + agentJarPath);
        }

        try {
            Files.createDirectories(JACOCO_DIR);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to prepare JaCoCo output directory at " + JACOCO_DIR, e);
        }

        String jacocoAgentOpts =
                "-javaagent:/jacoco/jacocoagent.jar=output=tcpserver,address=0.0.0.0,port="
                        + JACOCO_TCP_PORT
                        + ",includes=com.example.*";

        return container
                .withCopyFileToContainer(MountableFile.forHostPath(agentJarPath), "/jacoco/jacocoagent.jar")
                .withEnv("RABBIT_HOST", "rabbitmq")
                .withEnv("RABBIT_PORT", "5672")
                .withEnv("RABBIT_USER", RABBIT_APP_USER)
                .withEnv("RABBIT_PASSWORD", RABBIT_APP_PASSWORD)
                .withEnv("POSTGRES_HOST", "postgres")
                .withEnv("POSTGRES_PORT", "5432")
                .withEnv("POSTGRES_DB", "app")
                .withEnv("POSTGRES_USER", DB_USER)
                .withEnv("POSTGRES_PASSWORD", DB_PASSWORD)
                .withEnv("SERVER_SHUTDOWN", "immediate")
                .withEnv("SPRING_LIFECYCLE_TIMEOUT_PER_SHUTDOWN_PHASE", "5s")
                .withEnv("JAVA_TOOL_OPTIONS", jacocoAgentOpts + " -Xms128m -Xmx512m")
                .withExposedPorts(8080, JACOCO_TCP_PORT)
                .withNetwork(network)
                .dependsOn(postgres, rabbitmq)
                .withStartupAttempts(3)
                .waitingFor(Wait.forHttp("/actuator/health").forStatusCode(200).withStartupTimeout(Duration.ofMinutes(5)))
                .withLogConsumer(frame -> printFrame("API", frame));
    }

    private static Path resolveLocalAppJar() {
        Path targetDir = Paths.get("target").toAbsolutePath();
        if (!Files.isDirectory(targetDir)) {
            throw new IllegalStateException("target directory not found at " + targetDir);
        }

        try (var stream = Files.list(targetDir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".jar"))
                    .filter(p -> !p.getFileName().toString().endsWith(".jar.original"))
                    .filter(p -> !p.getFileName().toString().endsWith("-sources.jar"))
                    .filter(p -> !p.getFileName().toString().endsWith("-javadoc.jar"))
                    .max(Comparator.comparingLong(p -> p.toFile().lastModified()))
                    .orElseThrow(() -> new IllegalStateException("No application jar found in " + targetDir));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to resolve application jar in " + targetDir, e);
        }
    }

    private void waitForRabbitReady() {
        ensureRabbitTopologyDeclared();
        waitForRabbitConsumer();
    }

    private void waitForRabbitConsumer() {
        AtomicInteger lastConsumers = new AtomicInteger(-1);
        AtomicReference<String> lastError = new AtomicReference<>("");

        try {
            Awaitility.await()
                    .atMost(Duration.ofMinutes(3))
                    .pollDelay(Duration.ofMillis(100))
                    .pollInterval(DEFAULT_POLL_INTERVAL)
                    .until(() -> {
                        try {
                            try (com.rabbitmq.client.Connection connection = openAmqpConnection();
                                 com.rabbitmq.client.Channel channel = connection.createChannel()) {
                                if (!queueExists(channel, RabbitTopologyConfig.EVENTS_QUEUE)) {
                                    lastConsumers.set(0);
                                    return false;
                                }
                                int consumers = channel.queueDeclarePassive(RabbitTopologyConfig.EVENTS_QUEUE).getConsumerCount();
                                lastConsumers.set(consumers);
                                return consumers > 0;
                            }
                        } catch (Exception e) {
                            lastError.set(e.toString());
                            lastConsumers.set(-1);
                            return false;
                        }
                    });
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Rabbit consumer not ready. consumers=" + lastConsumers.get() + " lastError=" + lastError.get(),
                    e
            );
        }
    }

    private void ensureRabbitTopologyDeclared() {
        AtomicReference<String> lastError = new AtomicReference<>("");
        try {
            Awaitility.await()
                    .atMost(Duration.ofMinutes(2))
                    .pollDelay(Duration.ofMillis(100))
                    .pollInterval(DEFAULT_POLL_INTERVAL)
                    .until(() -> {
                        try (com.rabbitmq.client.Connection connection = openAmqpConnection();
                             com.rabbitmq.client.Channel channel = connection.createChannel()) {
                            channel.exchangeDeclare(RabbitTopologyConfig.EVENTS_EXCHANGE, "direct", true);
                            channel.exchangeDeclare(RabbitTopologyConfig.DLX_EXCHANGE, "direct", true);

                            channel.queueDeclare(
                                    RabbitTopologyConfig.EVENTS_QUEUE,
                                    true,
                                    false,
                                    false,
                                    Map.of(
                                            "x-dead-letter-exchange", RabbitTopologyConfig.DLX_EXCHANGE,
                                            "x-dead-letter-routing-key", RabbitTopologyConfig.DLQ_ROUTING_KEY
                                    )
                            );
                            channel.queueDeclare(RabbitTopologyConfig.DLQ_QUEUE, true, false, false, Map.of());

                            channel.queueBind(
                                    RabbitTopologyConfig.EVENTS_QUEUE,
                                    RabbitTopologyConfig.EVENTS_EXCHANGE,
                                    RabbitTopologyConfig.EVENTS_ROUTING_KEY
                            );
                            channel.queueBind(
                                    RabbitTopologyConfig.DLQ_QUEUE,
                                    RabbitTopologyConfig.DLX_EXCHANGE,
                                    RabbitTopologyConfig.DLQ_ROUTING_KEY
                            );
                            return true;
                        } catch (Exception e) {
                            lastError.set(e.toString());
                            return false;
                        }
                    });
        } catch (Exception e) {
            throw new IllegalStateException("Rabbit topology not ready to declare via AMQP. lastError=" + lastError.get(), e);
        }
    }

    private com.rabbitmq.client.Connection openAmqpConnection() throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(rabbitmq.getHost());
        factory.setPort(rabbitmq.getMappedPort(5672));
        factory.setUsername(RABBIT_APP_USER);
        factory.setPassword(RABBIT_APP_PASSWORD);
        factory.setVirtualHost("/");
        factory.setAutomaticRecoveryEnabled(false);
        factory.setNetworkRecoveryInterval(1000);
        return factory.newConnection("it-" + RUN_ID);
    }

    private boolean queueExists(com.rabbitmq.client.Channel channel, String queueName) {
        try {
            channel.queueDeclarePassive(queueName);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void waitForApiHealthy() {
        AtomicInteger lastStatus = new AtomicInteger(-1);
        AtomicReference<String> lastBody = new AtomicReference<>("");

        try {
            Awaitility.await()
                    .atMost(Duration.ofMinutes(3))
                    .pollDelay(Duration.ofMillis(100))
                    .pollInterval(DEFAULT_POLL_INTERVAL)
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

