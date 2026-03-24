package com.comet.opik.api.resources.utils;

import com.comet.opik.infrastructure.DatabaseAnalyticsFactory;
import com.google.common.eventbus.EventBus;
import com.redis.testcontainers.RedisContainer;
import org.mockito.Mockito;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension;

/**
 * Encapsulates Testcontainers startup and Dropwizard app creation shared across
 * project-scoped resource integration tests that require MinIO (e.g. Optimizations).
 *
 * <p>Usage in test classes:</p>
 * <pre>{@code
 *   private final TestContainersSetupWithMinIO setup = new TestContainersSetupWithMinIO();
 *
 *   @RegisterApp
 *   private final TestDropwizardAppExtension APP = setup.APP;
 * }</pre>
 */
public class TestContainersSetupWithMinIO {

    public final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    public final MySQLContainer MYSQL = MySQLContainerUtils.newMySQLContainer();
    public final GenericContainer<?> ZOOKEEPER = ClickHouseContainerUtils.newZookeeperContainer();
    public final ClickHouseContainer CLICKHOUSE = ClickHouseContainerUtils.newClickHouseContainer(ZOOKEEPER);
    public final GenericContainer<?> MINIO = MinIOContainerUtils.newMinIOContainer();
    public final WireMockUtils.WireMockRuntime wireMock;
    public final TestDropwizardAppExtension APP;

    public TestContainersSetupWithMinIO() {
        Startables.deepStart(REDIS, MYSQL, CLICKHOUSE, ZOOKEEPER, MINIO).join();

        String minioUrl = "http://%s:%d".formatted(MINIO.getHost(), MINIO.getMappedPort(9000));

        wireMock = WireMockUtils.startWireMock();

        DatabaseAnalyticsFactory databaseAnalyticsFactory = ClickHouseContainerUtils
                .newDatabaseAnalyticsFactory(CLICKHOUSE, DATABASE_NAME);

        MigrationUtils.runMysqlDbMigration(MYSQL);
        MigrationUtils.runClickhouseDbMigration(CLICKHOUSE);
        MinIOContainerUtils.setupBucketAndCredentials(minioUrl);

        APP = newTestDropwizardAppExtension(
                TestDropwizardAppExtensionUtils.AppContextConfig.builder()
                        .jdbcUrl(MYSQL.getJdbcUrl())
                        .databaseAnalyticsFactory(databaseAnalyticsFactory)
                        .runtimeInfo(wireMock.runtimeInfo())
                        .redisUrl(REDIS.getRedisURI())
                        .authCacheTtlInSeconds(null)
                        .mockEventBus(Mockito.mock(EventBus.class))
                        .minioUrl(minioUrl)
                        .isMinIO(true)
                        .build());
    }
}
