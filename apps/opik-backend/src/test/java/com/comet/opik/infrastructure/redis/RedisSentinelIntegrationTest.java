package com.comet.opik.infrastructure.redis;

import com.comet.opik.api.Project;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.AppContextConfig;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.CustomConfig;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.RedisConfig;
import com.comet.opik.podam.PodamFactoryUtils;
import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import jakarta.inject.Singleton;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.GenericType;
import org.apache.hc.core5.http.HttpStatus;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.misc.RedisURI;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the backend with {@code redis.sentinel} enabled against a real master, replica and sentinel. A failover is not
 * exercised: sentinels in virtualised runners enter tilt mode on clock jitter, which suppresses it.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class RedisSentinelIntegrationTest {

    private static final DockerImageName REDIS_IMAGE = DockerImageName.parse("redis:7.2.4-alpine3.19");
    private static final String MASTER_ALIAS = "redis-master";
    private static final String REPLICA_ALIAS = "redis-replica";
    private static final int REDIS_PORT = 6379;
    private static final int SENTINEL_PORT = 26379;

    private static final GenericType<List<HealthCheckResponse>> HEALTH_CHECK_LIST = new GenericType<>() {
    };

    private final Network network = Network.newNetwork();
    private final GenericContainer<?> master = new GenericContainer<>(REDIS_IMAGE)
            .withNetwork(network)
            .withNetworkAliases(MASTER_ALIAS)
            .withExposedPorts(REDIS_PORT)
            .waitingFor(Wait.forListeningPort());
    // Announces its alias so the sentinel reports an address the NAT mapper can translate
    private final GenericContainer<?> replica = new GenericContainer<>(REDIS_IMAGE)
            .withNetwork(network)
            .withNetworkAliases(REPLICA_ALIAS)
            .withExposedPorts(REDIS_PORT)
            .withCommand("redis-server", "--replicaof", MASTER_ALIAS, String.valueOf(REDIS_PORT),
                    "--replica-announce-ip", REPLICA_ALIAS, "--replica-announce-port", String.valueOf(REDIS_PORT))
            .dependsOn(master)
            .waitingFor(Wait.forListeningPort());
    private final GenericContainer<?> sentinel = new GenericContainer<>(REDIS_IMAGE)
            .withNetwork(network)
            .withExposedPorts(SENTINEL_PORT)
            .withCopyFileToContainer(MountableFile.forClasspathResource("redis/sentinel.conf"),
                    "/etc/redis/sentinel.conf")
            .withCommand("redis-sentinel", "/etc/redis/sentinel.conf")
            .dependsOn(master, replica)
            .waitingFor(Wait.forLogMessage(".*\\+monitor master mymaster.*", 1));
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
                .redisUrl("redis://%s:%d/0".formatted(sentinel.getHost(), sentinel.getMappedPort(SENTINEL_PORT)))
                .customConfigs(List.of(
                        new CustomConfig("redis.sentinel.enabled", "true"),
                        new CustomConfig("redis.sentinel.masterName", "mymaster"),
                        // One sentinel keeps the topology deterministic
                        new CustomConfig("redis.sentinel.checkSentinelsList", "false")))
                // The general limit is counted in Redis, so a rejected second call proves commands reach the master
                .rateLimitEnabled(true)
                .limit(1L)
                .workspaceLimit(100L)
                .limitDurationInSeconds(60L)
                .modules(List.of(new NatMapperModule()))
                .build());
    }

    private final PodamFactory podamFactory = PodamFactoryUtils.newPodamFactory();

    private ClientSupport client;
    private String baseURI;

    @BeforeAll
    void setUpAll(ClientSupport client) {
        this.client = client;
        this.baseURI = TestUtils.getBaseUrl(client);
        ClientSupportUtils.config(client);
    }

    @Test
    void reportsRedisHealthyThroughTheSentinelDiscoveredMaster() {
        var expected = List.of(new HealthCheckResponse("redis", true, true, "READY"));

        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            try (var response = client.target("%s/health-check?name=redis".formatted(baseURI)).request().get()) {
                assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);
                assertThat(response.readEntity(HEALTH_CHECK_LIST)).isEqualTo(expected);
            }
        });
    }

    @Test
    void runsRedisBackedRateLimitingAgainstTheMaster() {
        assertThat(createProject()).isEqualTo(HttpStatus.SC_CREATED);
        assertThat(createProject()).isEqualTo(HttpStatus.SC_TOO_MANY_REQUESTS);
    }

    private int createProject() {
        var project = podamFactory.manufacturePojo(Project.class);
        try (var response = client.target("%s/v1/private/projects".formatted(baseURI))
                .request()
                .post(Entity.json(project))) {
            return response.getStatus();
        }
    }

    private record HealthCheckResponse(String name, boolean healthy, boolean critical, String type) {
    }

    /**
     * Sentinels announce the data nodes by their docker aliases, which the host cannot resolve. Only the test needs
     * this; everything else is the production wiring built from {@code redis} in the YAML config.
     */
    private class NatMapperModule extends AbstractModule {

        @Provides
        @Singleton
        public RedissonClient redisNonReactiveClient(@Config("redis") RedisConfig redisConfig) {
            var config = redisConfig.build();
            var containers = Map.of(MASTER_ALIAS, master, REPLICA_ALIAS, replica);
            config.useSentinelServers().setNatMapper(address -> {
                var container = containers.get(address.getHost());
                return container == null
                        ? address
                        : new RedisURI(address.getScheme(), container.getHost(),
                                container.getMappedPort(address.getPort()));
            });
            return Redisson.create(config);
        }
    }
}
