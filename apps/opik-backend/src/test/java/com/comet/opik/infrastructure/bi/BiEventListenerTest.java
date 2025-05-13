package com.comet.opik.infrastructure.bi;

import com.comet.opik.api.Trace;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.cache.CacheManager;
import com.comet.opik.podam.PodamFactoryUtils;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.redis.testcontainers.RedisContainer;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.shaded.org.awaitility.Awaitility;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;
import uk.co.jemos.podam.api.PodamUtils;

import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.MigrationUtils.CLICKHOUSE_CHANGELOG_FILE;
import static com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension;
import static com.comet.opik.infrastructure.bi.DailyUsageReportJobTest.SUCCESS_RESPONSE;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class BiEventListenerTest {

    private static final String USER = UUID.randomUUID().toString();
    private static final String VERSION = "%s.%s.%s".formatted(PodamUtils.getIntegerInRange(1, 99),
            PodamUtils.getIntegerInRange(1, 99), PodamUtils.getIntegerInRange(1, 99));

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final Network network = Network.newNetwork();
    private final MySQLContainer<?> MYSQL = MySQLContainerUtils.newMySQLContainer(false);
    private final GenericContainer<?> ZOOKEEPER_CONTAINER = ClickHouseContainerUtils.newZookeeperContainer(false,
            network);
    private final ClickHouseContainer CLICKHOUSE = ClickHouseContainerUtils.newClickHouseContainer(false, network,
            ZOOKEEPER_CONTAINER);

    private final WireMockUtils.WireMockRuntime wireMock;

    @RegisterApp
    private final TestDropwizardAppExtension APP;

    {
        Startables.deepStart(REDIS, MYSQL, CLICKHOUSE).join();

        try {
            MigrationUtils.runDbMigration(MYSQL.createConnection(""), MySQLContainerUtils.migrationParameters());
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        try (var connection = CLICKHOUSE.createConnection("")) {
            MigrationUtils.runClickhouseDbMigration(connection, CLICKHOUSE_CHANGELOG_FILE,
                    ClickHouseContainerUtils.migrationParameters());
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        wireMock = WireMockUtils.startWireMock();

        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(
                CLICKHOUSE, DATABASE_NAME);

        mockBiEventResponse(BiEventListener.FIRST_TRACE_REPORT_BI_EVENT, wireMock.server());

        APP = newTestDropwizardAppExtension(
                TestDropwizardAppExtensionUtils.AppContextConfig.builder()
                        .jdbcUrl(MYSQL.getJdbcUrl())
                        .databaseAnalyticsFactory(databaseAnalyticsFactory)
                        .redisUrl(REDIS.getRedisURI())
                        .runtimeInfo(wireMock.runtimeInfo())
                        .usageReportUrl("%s/v1/notify/event".formatted(wireMock.runtimeInfo().getHttpBaseUrl()))
                        .usageReportEnabled(true)
                        .metadataVersion(VERSION)
                        .build());
    }

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

    private String baseURI;
    private ClientSupport client;
    private TraceResourceClient traceResourceClient;

    @BeforeAll
    void setUpAll(ClientSupport client) {

        this.baseURI = "http://localhost:%d".formatted(client.getPort());
        this.client = client;

        ClientSupportUtils.config(client);

        traceResourceClient = new TraceResourceClient(this.client, baseURI);
    }

    @AfterAll
    void tearDownAll() {
        MYSQL.stop();
        wireMock.server().stop();
        CLICKHOUSE.stop();
        ZOOKEEPER_CONTAINER.stop();
        network.close();
    }

    private void mockTargetWorkspace(String apiKey, String workspaceName, String workspaceId) {
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, USER);
    }

    private void mockBiEventResponse(String eventType, WireMockServer server) {
        server.stubFor(
                post(urlPathEqualTo("/v1/notify/event"))
                        .withRequestBody(matchingJsonPath("$.anonymous_id", matching(
                                "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")))
                        .withRequestBody(matchingJsonPath("$.event_type",
                                matching(eventType)))
                        .withRequestBody(matchingJsonPath("$.event_properties.opik_app_version", matching(VERSION)))
                        .willReturn(WireMock.okJson(SUCCESS_RESPONSE)));
    }

    @Test
    void shouldReportFirstTraceCreatedEvent(UsageReportService usageReportService, CacheManager cacheManager) {
        var workspaceId = UUID.randomUUID().toString();
        var workspaceName = UUID.randomUUID().toString();
        var apiKey = UUID.randomUUID().toString();

        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        Trace trace = factory.manufacturePojo(Trace.class);

        cacheManager.evict(Metadata.FIRST_TRACE_CREATED.getValue(), false).block();

        traceResourceClient.createTrace(trace, apiKey, workspaceName);

        Awaitility
                .await()
                .atMost(60, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    verifyResponse(wireMock.server(), BiEventListener.FIRST_TRACE_REPORT_BI_EVENT);
                });

        Assertions.assertThat(usageReportService.isFirstTraceReport()).isTrue();

        trace = factory.manufacturePojo(Trace.class);

        traceResourceClient.createTrace(trace, apiKey, workspaceName);

        Awaitility
                .await()
                .during(1, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    wireMock.server().verify(1, postRequestedFor(urlPathEqualTo("/v1/notify/event"))
                            .withRequestBody(matchingJsonPath("$.event_type",
                                    equalTo(BiEventListener.FIRST_TRACE_REPORT_BI_EVENT))));
                });
    }

    private void verifyResponse(WireMockServer server, String eventType) {
        server.verify(
                postRequestedFor(urlPathEqualTo("/v1/notify/event"))
                        .withRequestBody(matchingJsonPath("$.anonymous_id", matching(
                                "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"))
                                .and(matchingJsonPath("$.event_type", equalTo(eventType)))
                                .and(matchingJsonPath("$.event_properties.opik_app_version", equalTo(VERSION)))));
    }

}
