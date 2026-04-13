package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.connect.CreateSessionRequest;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.AppContextConfig;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.OpikConnectResourceClient;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.redis.testcontainers.RedisContainer;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
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

import java.util.Base64;
import java.util.UUID;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static java.util.UUID.randomUUID;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Opik Connect Resource Test — rate limit")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class OpikConnectResourceRateLimitTest {

    private static final long LIMIT = 3L;
    private static final long WORKSPACE_LIMIT = 100L;
    private static final long LIMIT_DURATION_SECONDS = 60L;

    private static final String API_KEY = randomUUID().toString();
    private static final String USER = randomUUID().toString();
    private static final String WORKSPACE_ID = randomUUID().toString();
    private static final String TEST_WORKSPACE = randomUUID().toString();

    // Isolated containers (reusable=false, dedicated network) so the ClickHouse
    // replica path in ZooKeeper does not collide with the other opik-connect test classes
    // that use testcontainers' shared-reuse feature.
    private final Network network = Network.newNetwork();
    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer MYSQL_CONTAINER = MySQLContainerUtils.newMySQLContainer(false);
    private final GenericContainer<?> ZOOKEEPER_CONTAINER = ClickHouseContainerUtils.newZookeeperContainer(false,
            network);
    private final ClickHouseContainer CLICK_HOUSE_CONTAINER = ClickHouseContainerUtils
            .newClickHouseContainer(false, network, ZOOKEEPER_CONTAINER);

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
                AppContextConfig.builder()
                        .jdbcUrl(MYSQL_CONTAINER.getJdbcUrl())
                        .databaseAnalyticsFactory(databaseAnalyticsFactory)
                        .runtimeInfo(wireMock.runtimeInfo())
                        .redisUrl(REDIS.getRedisURI())
                        .rateLimitEnabled(true)
                        .limit(LIMIT)
                        .workspaceLimit(WORKSPACE_LIMIT)
                        .limitDurationInSeconds(LIMIT_DURATION_SECONDS)
                        .build());
    }

    private OpikConnectResourceClient connectClient;

    @BeforeAll
    void setUpAll(ClientSupport client) {
        var baseURI = TestUtils.getBaseUrl(client);
        ClientSupportUtils.config(client);
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), API_KEY, TEST_WORKSPACE, WORKSPACE_ID, USER);
        this.connectClient = new OpikConnectResourceClient(client, baseURI);
    }

    @AfterAll
    void tearDownAll() {
        wireMock.server().stop();
    }

    @Test
    @DisplayName("Exceeding the per-user limit on POST /sessions returns 429")
    void createSessionReturns429WhenLimitExceeded() {
        // Each call will 404 (project doesn't exist) until the rate limit kicks in.
        // The rate limit is checked before the service body runs, so 429 replaces the
        // 404 response once the limit is hit.
        CreateSessionRequest request = CreateSessionRequest.builder()
                .projectId(UUID.randomUUID())
                .activationKey(Base64.getEncoder().encodeToString(new byte[32]))
                .ttlSeconds(300)
                .build();

        int tooManyRequestsCount = 0;
        int otherCount = 0;
        // Fire twice the limit to guarantee at least LIMIT successful-bucket calls and
        // some 429s.
        for (int i = 0; i < LIMIT * 2; i++) {
            try (Response response = connectClient.callCreateSession(request, API_KEY, TEST_WORKSPACE)) {
                if (response.getStatus() == 429) {
                    tooManyRequestsCount++;
                } else {
                    otherCount++;
                }
            }
        }

        assertThat(tooManyRequestsCount).isGreaterThan(0);
        assertThat((long) (tooManyRequestsCount + otherCount)).isEqualTo(LIMIT * 2);
    }
}
