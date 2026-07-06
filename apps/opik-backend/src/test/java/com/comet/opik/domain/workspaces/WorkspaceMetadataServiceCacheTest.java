package com.comet.opik.domain.workspaces;

import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.AppContextConfig;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.CustomConfig;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.cache.CacheManager;
import com.google.inject.AbstractModule;
import com.redis.testcontainers.RedisContainer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards that the {@code project_metadata} cache on
 * {@link WorkspaceMetadataServiceImpl#getProjectMetadata(String, UUID)} actually engages: the
 * method must stay interceptable by Guice (not private) and the cache name must stay configured,
 * otherwise the expensive per-project size estimate silently runs on every call.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class WorkspaceMetadataServiceCacheTest {

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer MYSQL = MySQLContainerUtils.newMySQLContainer();
    private final GenericContainer<?> ZOOKEEPER_CONTAINER = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer CLICKHOUSE = ClickHouseContainerUtils.newClickHouseContainer(ZOOKEEPER_CONTAINER);

    private static final AtomicInteger PROJECT_METADATA_DAO_CALLS = new AtomicInteger();

    private static final WorkspaceMetadataDAO COUNTING_DAO = new WorkspaceMetadataDAO() {

        @Override
        public Mono<ScopeMetadata> getProjectMetadata(String workspaceId, UUID projectId) {
            PROJECT_METADATA_DAO_CALLS.incrementAndGet();
            return Mono.just(ScopeMetadata.builder()
                    .sizeGb(ThreadLocalRandom.current().nextDouble())
                    .totalTableSizeGb(100)
                    .percentageOfTable(1)
                    .limitSizeGb(10)
                    .build());
        }

        @Override
        public Mono<ExperimentScopeMetadata> getExperimentMetadata(String workspaceId, UUID datasetId) {
            return Mono.just(ExperimentScopeMetadata.builder().build());
        }
    };

    @RegisterApp
    private final TestDropwizardAppExtension APP;

    {
        Startables.deepStart(MYSQL, CLICKHOUSE, REDIS, ZOOKEEPER_CONTAINER).join();

        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(
                CLICKHOUSE, DATABASE_NAME);

        MigrationUtils.runMysqlDbMigration(MYSQL);
        MigrationUtils.runClickhouseDbMigration(CLICKHOUSE);

        APP = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                AppContextConfig.builder()
                        .jdbcUrl(MYSQL.getJdbcUrl())
                        .databaseAnalyticsFactory(databaseAnalyticsFactory)
                        .redisUrl(REDIS.getRedisURI())
                        .modules(List.of(new AbstractModule() {

                            @Override
                            protected void configure() {
                                bind(WorkspaceMetadataDAO.class).toInstance(COUNTING_DAO);
                            }

                        }))
                        .customConfigs(
                                List.of(
                                        new CustomConfig("cacheManager.enabled", "true"),
                                        new CustomConfig("cacheManager.caches.project_metadata", "PT30S")))
                        .build());
    }

    @Test
    void getProjectMetadata__repeatedCallsAreServedFromCache(WorkspaceMetadataService service,
            CacheManager cacheManager) {
        var impl = (WorkspaceMetadataServiceImpl) service;
        var workspaceId = UUID.randomUUID().toString();
        var projectId = UUID.randomUUID();

        var first = impl.getProjectMetadata(workspaceId, projectId).block();

        assertThat(first).isNotNull();
        assertThat(PROJECT_METADATA_DAO_CALLS.get()).isEqualTo(1);

        // the cache put is async and its completion signal is dropped, so wait until the entry is
        // actually readable before asserting the second call is served from the cache
        var cacheKey = "project_metadata:--%s-%s".formatted(workspaceId, projectId);
        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .until(() -> Boolean.TRUE.equals(cacheManager.contains(cacheKey).block()));

        // same key: served from cache, the DAO must not be called again
        var second = impl.getProjectMetadata(workspaceId, projectId).block();

        assertThat(second).isEqualTo(first);
        assertThat(PROJECT_METADATA_DAO_CALLS.get()).isEqualTo(1);

        // different project: cache miss, the DAO is called again
        var other = impl.getProjectMetadata(workspaceId, UUID.randomUUID()).block();

        assertThat(other).isNotEqualTo(first);
        assertThat(PROJECT_METADATA_DAO_CALLS.get()).isEqualTo(2);
    }
}
