package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.OpikVersion;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.AppContextConfig;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.CustomConfig;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.WorkspaceResourceClient;
import com.comet.opik.domain.workspaces.WorkspacesService;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.podam.PodamFactoryUtils;
import com.redis.testcontainers.RedisContainer;
import org.apache.commons.lang3.RandomStringUtils;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class WorkspaceVersionAnalyticsEmissionTest {

    private static final String API_KEY = "apiKey-" + UUID.randomUUID();
    private static final String ANALYTICS_PATH = "/v1/notify/event";
    private static final String EVENT_TYPE = "opik_workspace_version_determined";

    private final PodamFactory podamFactory = PodamFactoryUtils.newPodamFactory();

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final Network network = Network.newNetwork();
    private final GenericContainer<?> ZOOKEEPER = ClickHouseContainerUtils.newZookeeperContainer(false, network);
    private final ClickHouseContainer CLICKHOUSE = ClickHouseContainerUtils.newClickHouseContainer(
            false, network, ZOOKEEPER);
    private final MySQLContainer MYSQL = MySQLContainerUtils.newMySQLContainer(false);

    @RegisterApp
    private final TestDropwizardAppExtension app;

    private final WireMockUtils.WireMockRuntime wireMock;

    {
        wireMock = WireMockUtils.startWireMock();
        wireMock.server().stubFor(post(urlPathEqualTo(ANALYTICS_PATH)).willReturn(okJson("{\"status\":\"OK\"}")));

        Startables.deepStart(REDIS, MYSQL, CLICKHOUSE).join();

        MigrationUtils.runMysqlDbMigration(MYSQL);
        MigrationUtils.runClickhouseDbMigration(CLICKHOUSE);

        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(CLICKHOUSE, DATABASE_NAME);

        app = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                AppContextConfig.builder()
                        .redisUrl(REDIS.getRedisURI())
                        .jdbcUrl(MYSQL.getJdbcUrl())
                        .databaseAnalyticsFactory(databaseAnalyticsFactory)
                        .runtimeInfo(wireMock.runtimeInfo())
                        .usageReportUrl("%s%s".formatted(wireMock.runtimeInfo().getHttpBaseUrl(), ANALYTICS_PATH))
                        .usageReportEnabled(true)
                        .customConfigs(List.of(
                                new CustomConfig("analytics.enabled", "true"),
                                new CustomConfig("cacheManager.enabled", "true"),
                                new CustomConfig("cacheManager.caches.workspace_version", "PT5M")))
                        .build());
    }

    private WorkspaceResourceClient workspaceClient;

    @BeforeAll
    void beforeAll(ClientSupport clientSupport) {
        var baseUrl = TestUtils.getBaseUrl(clientSupport);
        ClientSupportUtils.config(clientSupport);
        workspaceClient = new WorkspaceResourceClient(clientSupport, baseUrl, podamFactory);
    }

    @AfterAll
    void afterAll() {
        wireMock.server().stop();
    }

    @Test
    void firstDetermination__emitsEventWithVersionChangedTrue() {
        var workspaceId = UUID.randomUUID().toString();
        var workspaceName = mockWorkspace(workspaceId);

        workspaceClient.getWorkspaceVersion(API_KEY, workspaceName);

        Awaitility.await().atMost(5, TimeUnit.SECONDS).untilAsserted(
                () -> verifyEventCount(workspaceId, 1, "true", "none", "version_2"));
    }

    @Test
    void cacheHit__doesNotEmitAdditionalEvent() {
        var workspaceId = UUID.randomUUID().toString();
        var workspaceName = mockWorkspace(workspaceId);

        workspaceClient.getWorkspaceVersion(API_KEY, workspaceName);
        Awaitility.await().atMost(5, TimeUnit.SECONDS).untilAsserted(
                () -> verifyEventCount(workspaceId, 1, "true", "none", "version_2"));

        // Cache hit — must not emit a second event for this workspace.
        workspaceClient.getWorkspaceVersion(API_KEY, workspaceName);
        Awaitility.await()
                .during(500, TimeUnit.MILLISECONDS)
                .atMost(2, TimeUnit.SECONDS)
                .untilAsserted(() -> verifyEventCount(workspaceId, 1, "true", "none", "version_2"));
    }

    @Test
    void sameVersionRecompute__emitsVersionChangedFalse(WorkspacesService workspacesService) {
        var workspaceId = UUID.randomUUID().toString();
        var workspaceName = mockWorkspace(workspaceId);
        workspacesService.upsertVersion(workspaceId, OpikVersion.VERSION_2, Instant.now());

        workspaceClient.getWorkspaceVersion(API_KEY, workspaceName);

        Awaitility.await().atMost(5, TimeUnit.SECONDS).untilAsserted(
                () -> verifyEventCount(workspaceId, 1, "false", "version_2", "version_2"));
    }

    @Test
    void differentVersionRecompute__emitsVersionChangedTrue(WorkspacesService workspacesService) {
        var workspaceId = UUID.randomUUID().toString();
        var workspaceName = mockWorkspace(workspaceId);
        workspacesService.upsertVersion(workspaceId, OpikVersion.VERSION_1, Instant.now());

        workspaceClient.getWorkspaceVersion(API_KEY, workspaceName);

        Awaitility.await().atMost(5, TimeUnit.SECONDS).untilAsserted(
                () -> verifyEventCount(workspaceId, 1, "true", "version_1", "version_2"));
    }

    private String mockWorkspace(String workspaceId) {
        var workspaceName = "workspace-" + RandomStringUtils.secure().nextAlphanumeric(32);
        var user = "user-" + RandomStringUtils.secure().nextAlphanumeric(32);
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), API_KEY, workspaceName, workspaceId, user, null, null);
        return workspaceName;
    }

    private void verifyEventCount(String workspaceId, int expectedCount, String versionChanged,
            String previousVersion, String newVersion) {
        wireMock.server().verify(expectedCount,
                postRequestedFor(urlPathEqualTo(ANALYTICS_PATH))
                        .withRequestBody(matchingJsonPath("$.event_type", equalTo(EVENT_TYPE)))
                        .withRequestBody(matchingJsonPath("$.event_properties.workspace_id", equalTo(workspaceId)))
                        .withRequestBody(matchingJsonPath("$.event_properties.version_changed",
                                equalTo(versionChanged)))
                        .withRequestBody(matchingJsonPath("$.event_properties.previous_version",
                                equalTo(previousVersion)))
                        .withRequestBody(matchingJsonPath("$.event_properties.new_version", equalTo(newVersion))));
    }
}
