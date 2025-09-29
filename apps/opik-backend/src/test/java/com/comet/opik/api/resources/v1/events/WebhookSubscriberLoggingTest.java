package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.LogCriteria;
import com.comet.opik.api.events.webhooks.WebhookEvent;
import com.comet.opik.api.events.webhooks.WebhookEventTypes;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.v1.events.webhooks.WebhookHttpClient;
import com.comet.opik.domain.evaluators.UserLog;
import com.comet.opik.domain.evaluators.WebhookEventHandlerLogsDAO;
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
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.shaded.org.awaitility.Awaitility;
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
    private static final int MAX_RETRIES = 3;
    public static final String USER_AGENT = "Opik-Webhook/1.0";
    public static final String USER_NAME = "test-user-" + UUID.randomUUID();

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer<?> MYSQL_CONTAINER = MySQLContainerUtils.newMySQLContainer();
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
    private WebhookEventHandlerLogsDAO webhookEventHandlerLogsDAO;

    @BeforeAll
    void setUpAll(ConnectionFactory connectionFactory, RedissonReactiveClient redissonReactiveClient) {
        // Get real dependencies via parameter injection
        var userLogTableFactory = UserLogTableFactory.getInstance(connectionFactory);
        webhookEventHandlerLogsDAO = (WebhookEventHandlerLogsDAO) userLogTableFactory
                .getDAO(UserLog.WEBHOOK_EVENT_HANDLER);

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
        var webhookUrl = "http://localhost:" + externalWebhookServer.port() + "/webhook";
        var alertId = UUID.randomUUID();
        var eventId = "test-event-success-" + UUID.randomUUID();
        var webhookEvent = createWebhookEvent(webhookUrl, alertId, eventId);

        // Set up MDC context for logging
        externalWebhookServer.stubFor(post(urlEqualTo("/webhook"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader(HttpHeader.CONTENT_TYPE.toString(), MediaType.APPLICATION_JSON)
                        .withBody("{\"status\":\"success\"}")));

        // When
        StepVerifier.create(webhookSubscriber.processEvent(webhookEvent))
                .verifyComplete();

        // Then - verify HTTP call was made
        externalWebhookServer.verify(postRequestedFor(urlEqualTo("/webhook"))
                .withHeader(HttpHeader.CONTENT_TYPE.toString(), equalTo(MediaType.APPLICATION_JSON))
                .withHeader(HttpHeader.USER_AGENT.toString(), equalTo(USER_AGENT)));

        // And verify logs were created in the database
        Awaitility.await()
                .atMost(5, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    var criteria = LogCriteria.builder()
                            .markers(Map.of("alert_id", alertId.toString(), "event_id", eventId))
                            .page(1)
                            .size(10)
                            .build();

                    StepVerifier.create(
                            webhookEventHandlerLogsDAO.findLogs(criteria)
                                    .contextWrite(ctx -> setContent(ctx)))
                            .assertNext(logPage -> {
                                assertThat(logPage.content()).isNotEmpty();

                                var webhookLog = logPage.content().getFirst();
                                assertThat(webhookLog.workspaceId()).isEqualTo(WORKSPACE_ID);
                                assertThat(webhookLog.ruleId()).isNull(); // Webhook logs don't have rule IDs
                                assertThat(webhookLog.markers()).containsEntry("alert_id", alertId.toString());
                                assertThat(webhookLog.markers()).containsEntry("event_id", eventId);
                            })
                            .verifyComplete();
                });
    }

    private static @NotNull Context setContent(Context ctx) {
        return ctx.put(RequestContext.WORKSPACE_ID, WORKSPACE_ID)
                .put(RequestContext.USER_NAME, USER_NAME);
    }

    @Test
    void processEvent_whenWebhookFails_shouldRetryAndCreateErrorLogs() {
        // Given
        var webhookUrl = "http://localhost:" + externalWebhookServer.port() + "/webhook";
        var alertId = UUID.randomUUID();
        var eventId = "test-event-error-" + System.currentTimeMillis();
        var webhookEvent = createWebhookEvent(webhookUrl, alertId, eventId);

        externalWebhookServer.stubFor(post(urlEqualTo("/webhook"))
                .willReturn(aResponse()
                        .withStatus(500)
                        .withBody("Internal Server Error")));

        // When
        StepVerifier.create(webhookSubscriber.processEvent(webhookEvent))
                .verifyComplete();

        // Then - verify HTTP call was made with retries (1 initial + retries)
        externalWebhookServer.verify(MAX_RETRIES + 1, // 1 initial + MAX_RETRIES
                postRequestedFor(urlEqualTo("/webhook")));

        // And verify error logs were created in the database
        Awaitility.await()
                .atMost(5, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    var criteria = LogCriteria.builder()
                            .markers(Map.of("alert_id", alertId.toString(), "event_id", eventId))
                            .page(1)
                            .size(10)
                            .build();

                    StepVerifier.create(webhookEventHandlerLogsDAO.findLogs(criteria)
                            .contextWrite(ctx -> setContent(ctx)))
                            .assertNext(logPage -> {
                                assertThat(logPage.content()).isNotEmpty();

                                var errorLog = logPage.content().getFirst();
                                assertThat(errorLog.workspaceId()).isEqualTo(WORKSPACE_ID);
                                assertThat(errorLog.ruleId()).isNull();
                                assertThat(errorLog.markers()).containsEntry("alert_id", alertId.toString());
                                assertThat(errorLog.markers()).containsEntry("event_id", eventId);
                            })
                            .verifyComplete();
                });
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
                .eventType(WebhookEventTypes.TRACE_CREATED)
                .alertId(alertId)
                .workspaceId(WORKSPACE_ID)
                .userName(USER_NAME)
                .url(url)
                .payload(Map.of("message", "test payload", "timestamp", Instant.now().toString()))
                .createdAt(Instant.now())
                .maxRetries(MAX_RETRIES)
                .headers(Map.of())
                .build();
    }
}