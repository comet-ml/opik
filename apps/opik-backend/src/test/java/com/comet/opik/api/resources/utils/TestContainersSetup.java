package com.comet.opik.api.resources.utils;

import com.comet.opik.infrastructure.DatabaseAnalyticsFactory;
import com.google.common.eventbus.EventBus;
import com.redis.testcontainers.RedisContainer;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;

/**
 * Encapsulates Testcontainers startup and Dropwizard app creation shared across
 * project-scoped resource integration tests that do not require MinIO.
 *
 * <p>Usage in test classes:</p>
 * <pre>{@code
 *   private final TestContainersSetup setup = new TestContainersSetup();
 *
 *   @RegisterApp
 *   private final TestDropwizardAppExtension APP = setup.APP;
 * }</pre>
 *
 * <p>For tests that require a mocked EventBus:</p>
 * <pre>{@code
 *   private final TestContainersSetup setup = new TestContainersSetup(Mockito.mock(EventBus.class));
 * }</pre>
 */
public class TestContainersSetup {

    public final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    public final MySQLContainer MYSQL = MySQLContainerUtils.newMySQLContainer();
    public final GenericContainer<?> ZOOKEEPER = ClickHouseContainerUtils.newZookeeperContainer();
    public final ClickHouseContainer CLICKHOUSE = ClickHouseContainerUtils.newClickHouseContainer(ZOOKEEPER);
    public final WireMockUtils.WireMockRuntime wireMock;
    public final TestDropwizardAppExtension APP;

    public TestContainersSetup() {
        this(null);
    }

    public TestContainersSetup(EventBus mockEventBus) {
        Startables.deepStart(REDIS, MYSQL, CLICKHOUSE, ZOOKEEPER).join();

        wireMock = WireMockUtils.startWireMock();

        DatabaseAnalyticsFactory databaseAnalyticsFactory = ClickHouseContainerUtils
                .newDatabaseAnalyticsFactory(CLICKHOUSE, DATABASE_NAME);

        MigrationUtils.runMysqlDbMigration(MYSQL);
        MigrationUtils.runClickhouseDbMigration(CLICKHOUSE);

        if (mockEventBus != null) {
            APP = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                    TestDropwizardAppExtensionUtils.AppContextConfig.builder()
                            .jdbcUrl(MYSQL.getJdbcUrl())
                            .databaseAnalyticsFactory(databaseAnalyticsFactory)
                            .runtimeInfo(wireMock.runtimeInfo())
                            .redisUrl(REDIS.getRedisURI())
                            .authCacheTtlInSeconds(null)
                            .mockEventBus(mockEventBus)
                            .build());
        } else {
            APP = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                    MYSQL.getJdbcUrl(), databaseAnalyticsFactory, wireMock.runtimeInfo(), REDIS.getRedisURI());
        }
    }
}
