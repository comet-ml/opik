package com.comet.opik.infrastructure.redis;

import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.AppContextConfig;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.CustomConfig;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.resources.HealthCheckResourceClient;
import com.comet.opik.api.resources.utils.resources.HealthCheckResourceClient.HealthCheckResponse;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.ratelimit.RateLimited;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Response;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.hc.core5.http.HttpStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the backend with {@code redis.sentinel} enabled against a real master, replica and sentinel, through the
 * production client wiring. A failover is not exercised: sentinels in virtualised runners enter tilt mode on clock
 * jitter, which suppresses it.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class RedisSentinelIntegrationTest {

    private final RedisContainerUtils.StartedSentinel sentinel = RedisContainerUtils
            .startSentinel("master-" + RandomStringUtils.secure().nextAlphanumeric(8));
    private final RedisContainerUtils.SentinelTopology topology = sentinel.topology();
    private final MySQLContainer mysql = MySQLContainerUtils.newMySQLContainer();
    private final GenericContainer<?> zookeeper = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer clickHouse = ClickHouseContainerUtils.newClickHouseContainer(zookeeper);

    @RegisterApp
    private final TestDropwizardAppExtension app;

    {
        Startables.deepStart(mysql, clickHouse, zookeeper).join();
        MigrationUtils.runMysqlDbMigration(mysql);
        MigrationUtils.runClickhouseDbMigration(clickHouse);

        app = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(AppContextConfig.builder()
                .jdbcUrl(mysql.getJdbcUrl())
                .databaseAnalyticsFactory(ClickHouseContainerUtils.newDatabaseAnalyticsFactory(clickHouse,
                        DATABASE_NAME))
                .redisUrl(topology.sentinelUrl(sentinel.container().getHost()))
                .customConfigs(List.of(
                        new CustomConfig("redis.sentinel.enabled", "true"),
                        new CustomConfig("redis.sentinel.masterName", topology.masterName()),
                        // One sentinel keeps the topology deterministic
                        new CustomConfig("redis.sentinel.checkSentinelsList", "false")))
                // The general limit is counted in Redis, so a rejected second call proves commands reach the master
                .rateLimitEnabled(true)
                .limit(1L)
                .workspaceLimit(100L)
                .limitDurationInSeconds(60L)
                .build());
    }

    /**
     * Rate limited and side-effect free: the limit lives only in Redis, so the test proves commands reach the master
     * without writing to the shared MySQL container, which other test classes read.
     */
    @Path(PROBE_PATH)
    public static class RateLimitedProbe {

        @POST
        @RateLimited
        public Response probe() {
            return Response.status(Response.Status.CREATED).build();
        }
    }

    private static final String PROBE_PATH = "/v1/private/redis-sentinel-probe";

    private ClientSupport client;
    private String baseURI;
    private HealthCheckResourceClient healthCheckClient;

    @BeforeAll
    void setUpAll(ClientSupport client) {
        this.client = client;
        this.baseURI = TestUtils.getBaseUrl(client);
        ClientSupportUtils.config(client);
        this.healthCheckClient = new HealthCheckResourceClient(client, baseURI);
    }

    // The only container this class does not reuse
    @AfterAll
    void tearDownAll() {
        sentinel.container().stop();
    }

    @Test
    void reportsRedisHealthyThroughTheSentinelDiscoveredMaster() {
        var expected = HealthCheckResponse.builder().name("redis").healthy(true).critical(true).type("READY").build();

        healthCheckClient.awaitHealthCheck("redis", List.of(expected));
    }

    @Test
    void runsRedisBackedRateLimitingAgainstTheMaster() {
        assertThat(callProbe()).isEqualTo(HttpStatus.SC_CREATED);
        assertThat(callProbe()).isEqualTo(HttpStatus.SC_TOO_MANY_REQUESTS);
    }

    private int callProbe() {
        try (var response = client.target(baseURI + PROBE_PATH).request().post(Entity.json(""))) {
            return response.getStatus();
        }
    }
}
