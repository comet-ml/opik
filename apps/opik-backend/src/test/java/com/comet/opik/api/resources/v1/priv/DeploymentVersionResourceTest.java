package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.DeploymentVersion.DeploymentVersionHistory;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.domain.DeploymentVersionService;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.google.inject.Injector;
import com.redis.testcontainers.RedisContainer;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.AppContextConfig;
import static com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.CustomConfig;
import static com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension;
import static com.comet.opik.infrastructure.auth.RequestContext.WORKSPACE_HEADER;
import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Deployment Version Resource Test")
@ExtendWith(DropwizardAppExtensionProvider.class)
class DeploymentVersionResourceTest {

    private static final String API_KEY = UUID.randomUUID().toString();
    private static final String USER = UUID.randomUUID().toString();
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String TEST_WORKSPACE = UUID.randomUUID().toString();

    private static final String RESOURCE_PATH = "%s/v1/private/deployment/versions";

    private static final String TEST_VERSION = "9.9.9";

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer MYSQL = MySQLContainerUtils.newMySQLContainer();
    private final GenericContainer<?> ZOOKEEPER = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer CLICKHOUSE = ClickHouseContainerUtils.newClickHouseContainer(ZOOKEEPER);

    private final WireMockUtils.WireMockRuntime wireMock;

    @RegisterApp
    private final TestDropwizardAppExtension APP;

    {
        Startables.deepStart(REDIS, MYSQL, CLICKHOUSE, ZOOKEEPER).join();
        wireMock = WireMockUtils.startWireMock();
        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(CLICKHOUSE, DATABASE_NAME);

        MigrationUtils.runMysqlDbMigration(MYSQL);
        MigrationUtils.runClickhouseDbMigration(CLICKHOUSE);

        APP = newTestDropwizardAppExtension(
                AppContextConfig.builder()
                        .jdbcUrl(MYSQL.getJdbcUrl())
                        .databaseAnalyticsFactory(databaseAnalyticsFactory)
                        .redisUrl(REDIS.getRedisURI())
                        .runtimeInfo(wireMock.runtimeInfo())
                        .customConfigs(List.of(new CustomConfig("metadata.version", TEST_VERSION)))
                        .build());
    }

    private ClientSupport client;
    private String baseURI;
    private DeploymentVersionService deploymentVersionService;

    @BeforeAll
    void beforeAll(ClientSupport client, Injector injector) {
        this.client = client;
        this.baseURI = TestUtils.getBaseUrl(client);
        ClientSupportUtils.config(client);
        this.deploymentVersionService = injector.getInstance(DeploymentVersionService.class);

        AuthTestUtils.mockTargetWorkspace(wireMock.server(), API_KEY, TEST_WORKSPACE, WORKSPACE_ID, USER);
    }

    private DeploymentVersionHistory getVersionHistory() {
        try (var response = client.target(RESOURCE_PATH.formatted(baseURI))
                .request()
                .header(HttpHeaders.AUTHORIZATION, API_KEY)
                .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                .get()) {

            assertThat(response.getStatus()).isEqualTo(Response.Status.OK.getStatusCode());
            return response.readEntity(DeploymentVersionHistory.class);
        }
    }

    @Test
    @DisplayName("returns the running version and records it as this install's first version")
    void returnsRunningVersionAndRecordsIt() {
        var history = getVersionHistory();

        assertThat(history.currentVersion()).isEqualTo(TEST_VERSION);
        assertThat(history.versions()).hasSize(1);
        assertThat(history.versions().getFirst().version()).isEqualTo(TEST_VERSION);
        assertThat(history.versions().getFirst().firstSeenAt()).isNotNull();
    }

    @Test
    @DisplayName("concurrent replicas booting the same version produce exactly one history row")
    void concurrentStartsProduceASingleRow() throws InterruptedException {
        var before = getVersionHistory();
        var originalFirstSeenAt = before.versions().getFirst().firstSeenAt();

        // Stand in for N backend replicas booting at once — they all race to record the same version.
        int replicas = 8;
        var startLine = new CountDownLatch(1);
        var finishLine = new CountDownLatch(replicas);

        try (var executor = Executors.newFixedThreadPool(replicas)) {
            for (int i = 0; i < replicas; i++) {
                executor.submit(() -> {
                    try {
                        startLine.await();
                        deploymentVersionService.start();
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    } finally {
                        finishLine.countDown();
                    }
                });
            }

            startLine.countDown();
            assertThat(finishLine.await(30, TimeUnit.SECONDS)).isTrue();
        }

        var after = getVersionHistory();

        assertThat(after.versions()).hasSize(1);
        // The first writer's timestamp must survive: it is when this deployment actually started
        // on the version, and later replicas must not overwrite it.
        assertThat(after.versions().getFirst().firstSeenAt()).isEqualTo(originalFirstSeenAt);
    }
}
