package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.AlertEventType;
import com.comet.opik.api.AlertType;
import com.comet.opik.api.LogCriteria;
import com.comet.opik.api.events.webhooks.WebhookEvent;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.v1.events.webhooks.WebhookHttpClient;
import com.comet.opik.domain.alerts.AlertEventLogsDAO;
import com.comet.opik.domain.evaluators.UserLog;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.WebhookConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.log.tables.UserLogTableFactory;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.redis.testcontainers.RedisContainer;
import io.dropwizard.util.Duration;
import io.r2dbc.spi.ConnectionFactory;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.core.MediaType;
import org.awaitility.Awaitility;
import org.eclipse.jetty.http.HttpHeader;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.redisson.api.RedissonReactiveClient;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import reactor.test.StepVerifier;
import reactor.util.context.Context;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class WebhookSubscriberLoggingTest {

    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String USER_NAME = "test-user-" + UUID.randomUUID();
    private static final String USER_AGENT = "Opik-Webhook/1.0";

    // Test configuration constants
    private static final int MAX_RETRIES = 3;
    private static final String WEBHOOK_PATH = "/webhook";
    private static final int AWAIT_TIMEOUT_SECONDS = 5;
    private static final int AWAIT_POLL_INTERVAL_MS = 500;

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer MYSQL_CONTAINER = MySQLContainerUtils.newMySQLContainer();
    private final GenericContainer<?> ZOOKEEPER_CONTAINER = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer CLICK_HOUSE_CONTAINER = ClickHouseContainerUtils
            .newClickHouseContainer(ZOOKEEPER_CONTAINER);

    private final WireMockUtils.WireMockRuntime wireMock;

    @RegisterApp
    private final TestDropwizardAppExtension APP;

    {
        Startables.deepStart(REDIS, MYSQL_CONTAINER, CLICK_HOUSE_CONTAINER, ZOOKEEPER_CONTAINER).join();

        wireMock = WireMockUtils.startWireMock();

        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(
                CLICK_HOUSE_CONTAINER, DATABASE_NAME);

        MigrationUtils.runMysqlDbMigration(MYSQL_CONTAINER);
        MigrationUtils.runClickhouseDbMigration(CLICK_HOUSE_CONTAINER);

        APP = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                MYSQL_CONTAINER.getJdbcUrl(), databaseAnalyticsFactory, wireMock.runtimeInfo(), REDIS.getRedisURI());
    }

    private WireMockServer externalWebhookServer;
    private WebhookConfig webhookConfig;
    private WebhookHttpClient webhookHttpClient;
    private WebhookSubscriber webhookSubscriber;
    private AlertEventLogsDAO alertEventLogsDAO;

    @BeforeAll
    void setUpAll(ConnectionFactory connectionFactory, RedissonReactiveClient redissonReactiveClient) {
        // Get real dependencies via parameter injection
        var userLogTableFactory = UserLogTableFactory.getInstance(connectionFactory);
        alertEventLogsDAO = (AlertEventLogsDAO) userLogTableFactory
                .getDAO(UserLog.ALERT_EVENT);

        // Set up external webhook server
        setupWireMock();

        webhookConfig = createWebhookConfig();

        Client httpClient = ClientBuilder.newClient();

        // Create real WebhookHttpClient
        webhookHttpClient = new WebhookHttpClient(httpClient, webhookConfig);

        // Create real WebhookSubscriber
        webhookSubscriber = new WebhookSubscriber(webhookConfig, redissonReactiveClient, webhookHttpClient);
    }

    private void setupWireMock() {
        externalWebhookServer = new WireMockServer(0);
        externalWebhookServer.start();
    }

    @AfterAll
    void tearDownAll() {
        if (externalWebhookServer != null) {
            externalWebhookServer.stop();
        }
    }

    @BeforeEach
    void setUp() {
        externalWebhookServer.resetAll();
    }

    @Test
    void processEvent_whenSuccessfulWebhook_shouldSendRequestAndCreateLogs() {
        // Given
        var webhookUrl = "http://localhost:" + externalWebhookServer.port() + WEBHOOK_PATH;
        var alertId = UUID.randomUUID();
        var eventId = "test-event-success-" + UUID.randomUUID();
        var webhookEvent = createWebhookEvent(webhookUrl, alertId, eventId);

        // Set up MDC context for logging
        externalWebhookServer.stubFor(post(urlEqualTo(WEBHOOK_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader(HttpHeader.CONTENT_TYPE.toString(), MediaType.APPLICATION_JSON)
                        .withBody("{\"status\":\"success\"}")));

        // When
        StepVerifier.create(webhookSubscriber.processEvent(webhookEvent))
                .verifyComplete();

        // Then - verify HTTP call was made
        externalWebhookServer.verify(postRequestedFor(urlEqualTo(WEBHOOK_PATH))
                .withHeader(HttpHeader.CONTENT_TYPE.toString(), equalTo(MediaType.APPLICATION_JSON))
                .withHeader(HttpHeader.USER_AGENT.toString(), equalTo(USER_AGENT)));

        // And verify logs were created in the database
        assertLogsCreated(alertId, eventId);
    }

    private static @NotNull Context setContent(Context ctx) {
        return ctx.put(RequestContext.WORKSPACE_ID, WORKSPACE_ID)
                .put(RequestContext.USER_NAME, USER_NAME);
    }

    @Test
    void processEvent_whenWebhookFails_shouldRetryAndCreateErrorLogs() {
        // Given
        var webhookUrl = "http://localhost:" + externalWebhookServer.port() + WEBHOOK_PATH;
        var alertId = UUID.randomUUID();
        var eventId = "test-event-error-" + System.currentTimeMillis();
        var webhookEvent = createWebhookEvent(webhookUrl, alertId, eventId);

        externalWebhookServer.stubFor(post(urlEqualTo(WEBHOOK_PATH))
                .willReturn(aResponse()
                        .withStatus(500)
                        .withBody("Internal Server Error")));

        // When
        StepVerifier.create(webhookSubscriber.processEvent(webhookEvent))
                .verifyComplete();

        // Then - verify HTTP call was made with retries (1 initial + MAX_RETRIES retries)
        externalWebhookServer.verify(MAX_RETRIES + 1, // 1 initial + MAX_RETRIES retries
                postRequestedFor(urlEqualTo(WEBHOOK_PATH)));

        // And verify error logs were created in the database
        assertLogsCreated(alertId, eventId);
    }

    private WebhookConfig createWebhookConfig() {
        var config = new WebhookConfig();
        config.setEnabled(true);
        config.setMaxRetries(2); // Reduced for faster tests
        config.setInitialRetryDelay(Duration.milliseconds(50));
        config.setMaxRetryDelay(Duration.milliseconds(200));
        config.setRequestTimeout(Duration.seconds(2));
        config.setConnectionTimeout(Duration.seconds(1));
        config.setConsumerBatchSize(10);
        config.setPoolingInterval(Duration.milliseconds(500));
        return config;
    }

    private WebhookEvent<?> createWebhookEvent(String url, UUID alertId, String eventId) {
        return WebhookEvent.builder()
                .id(eventId)
                .alertName("Test Alert")
                .alertType(AlertType.GENERAL)
                .eventType(AlertEventType.PROMPT_CREATED)
                .alertId(alertId)
                .workspaceId(WORKSPACE_ID)
                .url(url)
                .payload(Map.of("message", "test payload", "timestamp", Instant.now().toString()))
                .createdAt(Instant.now())
                .maxRetries(MAX_RETRIES)
                .headers(Map.of())
                .build();
    }

    /**
     * Helper method to build log criteria for querying alert event logs.
     */
    private LogCriteria createLogCriteria(UUID alertId, String eventId) {
        return LogCriteria.builder()
                .markers(Map.of("alert_id", alertId.toString(), "event_id", eventId))
                .page(1)
                .size(10)
                .build();
    }

    /**
     * Helper method to wait for logs to be created and assert their presence.
     */
    private void assertLogsCreated(UUID alertId, String eventId) {
        Awaitility.await()
                .atMost(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .pollInterval(AWAIT_POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    var criteria = createLogCriteria(alertId, eventId);

                    StepVerifier.create(
                            alertEventLogsDAO.findLogs(criteria)
                                    .collectList()
                                    .contextWrite(ctx -> setContent(ctx)))
                            .assertNext(logs -> {
                                assertThat(logs).isNotEmpty();

                                var log = logs.getFirst();
                                assertThat(log.workspaceId()).isEqualTo(WORKSPACE_ID);
                                assertThat(log.markers()).containsEntry("alert_id", alertId.toString());
                                assertThat(log.markers()).containsEntry("event_id", eventId);
                            })
                            .verifyComplete();
                });
    }
}