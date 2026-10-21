package com.comet.opik.infrastructure.redis;

import com.comet.opik.api.Project;
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
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.podam.PodamFactoryUtils;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.hc.core5.http.HttpStatus;
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
import uk.co.jemos.podam.api.PodamFactory;

import java.util.List;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.domain.ProjectService.DEFAULT_WORKSPACE_NAME;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the backend with {@code redis.sentinel} enabled against a real master, replica and sentinel, through the
 * production client wiring. A failover is not exercised: sentinels in virtualised runners enter tilt mode on clock
 * jitter, which suppresses it.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class RedisSentinelIntegrationTest {

    private final RedisContainerUtils.SentinelTopology topology = RedisContainerUtils
            .newSentinelTopology("master-" + RandomStringUtils.secure().nextAlphanumeric(8));
    private final GenericContainer<?> sentinel = RedisContainerUtils.newSentinelContainer(topology);
    private final MySQLContainer mysql = MySQLContainerUtils.newMySQLContainer();
    private final GenericContainer<?> zookeeper = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer clickHouse = ClickHouseContainerUtils.newClickHouseContainer(zookeeper);

    @RegisterApp
    private final TestDropwizardAppExtension app;

    {
        Startables.deepStart(sentinel, mysql, clickHouse, zookeeper).join();
        MigrationUtils.runMysqlDbMigration(mysql);
        MigrationUtils.runClickhouseDbMigration(clickHouse);

        app = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(AppContextConfig.builder()
                .jdbcUrl(mysql.getJdbcUrl())
                .databaseAnalyticsFactory(ClickHouseContainerUtils.newDatabaseAnalyticsFactory(clickHouse,
                        DATABASE_NAME))
                .redisUrl(topology.sentinelUrl(sentinel.getHost()))
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

    private final PodamFactory podamFactory = PodamFactoryUtils.newPodamFactory();

    private HealthCheckResourceClient healthCheckClient;
    private ProjectResourceClient projectResourceClient;

    @BeforeAll
    void setUpAll(ClientSupport client) {
        var baseURI = TestUtils.getBaseUrl(client);
        ClientSupportUtils.config(client);
        this.healthCheckClient = new HealthCheckResourceClient(client, baseURI);
        this.projectResourceClient = new ProjectResourceClient(client, baseURI, podamFactory);
    }

    @Test
    void reportsRedisHealthyThroughTheSentinelDiscoveredMaster() {
        var expected = HealthCheckResponse.builder().name("redis").healthy(true).critical(true).type("READY").build();

        healthCheckClient.awaitHealthCheck("redis", List.of(expected));
    }

    @Test
    void runsRedisBackedRateLimitingAgainstTheMaster() {
        assertThat(createProject()).isEqualTo(HttpStatus.SC_CREATED);
        assertThat(createProject()).isEqualTo(HttpStatus.SC_TOO_MANY_REQUESTS);
    }

    private int createProject() {
        var apiKey = RandomStringUtils.secure().nextAlphanumeric(20);
        try (var response = projectResourceClient.callCreateProject(podamFactory.manufacturePojo(Project.class), apiKey,
                DEFAULT_WORKSPACE_NAME)) {
            return response.getStatus();
        }
    }
}
