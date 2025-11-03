package com.comet.opik.infrastructure.cache;

import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.AppContextConfig;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.CustomConfig;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.google.inject.AbstractModule;
import com.google.inject.Singleton;
import com.redis.testcontainers.RedisContainer;
import org.assertj.core.api.Assertions;
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

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class CacheManagerTest {

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer MYSQL = MySQLContainerUtils.newMySQLContainer();
    private final GenericContainer<?> ZOOKEEPER_CONTAINER = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer CLICKHOUSE = ClickHouseContainerUtils.newClickHouseContainer(ZOOKEEPER_CONTAINER);

    static final String CACHE_NAME_1 = "test";
    static final String CACHE_NAME_2 = "test2";

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
                                bind(CachedService.class).in(Singleton.class);
                            }

                        }))
                        .customConfigs(
                                List.of(
                                        new CustomConfig("cacheManager.enabled", "true"),
                                        new CustomConfig("cacheManager.defaultDuration", "PT0.500S"),
                                        new CustomConfig("cacheManager.caches.%s".formatted(CACHE_NAME_2), "PT0.200S")))
                        .build());
    }

    @Test
    void testCacheable__whenCacheableExpire__shouldCallRealMethodAgain(CachedService service) {

        String id = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();

        // first call, should call real method
        var dto = service.get(id, workspaceId);

        // second call, should return cached value
        var dto2 = service.get(id, workspaceId);

        Assertions.assertThat(dto).isEqualTo(dto2);

        // wait for cache to expire
        Mono.delay(Duration.ofMillis(1000)).block();

        // third call, should call real method again
        var dto3 = service.get(id, workspaceId);

        Assertions.assertThat(dto).isNotEqualTo(dto3);
    }

    @Test
    void testCachePut__whenCachePut__shouldUpdateCache(CachedService service) {

        String id = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();

        // first call, should call real method
        var dto = service.get(id, workspaceId);

        // second call, should return cached value
        var dto2 = service.get(id, workspaceId);

        Assertions.assertThat(dto).isEqualTo(dto2);

        // update value
        var updatedDto = new CachedService.DTO(id, workspaceId, UUID.randomUUID().toString());
        updatedDto = service.update(id, workspaceId, updatedDto);

        // third call, should return updated value
        var dto3 = service.get(id, workspaceId);

        Assertions.assertThat(updatedDto).isEqualTo(dto3);
    }

    @Test
    void testCacheEvict__whenCacheEvict__shouldRemoveCache(CachedService service) {

        String id = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();

        // first call, should call real method
        var dto = service.get(id, workspaceId);

        // second call, should return cached value
        var dto2 = service.get(id, workspaceId);

        Assertions.assertThat(dto).isEqualTo(dto2);

        // evict cache
        service.evict(id, workspaceId);

        // third call, should call real method again
        var dto3 = service.get(id, workspaceId);

        Assertions.assertThat(dto).isNotEqualTo(dto3);
    }

    @Test
    void testCacheable__whenCacheableExpire__shouldCallRealMethodAgain2(CachedService service) {

        String id = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();

        // first call, should call real method
        var dto = service.get2(id, workspaceId).block();

        // second call, should return cached value
        var dto2 = service.get2(id, workspaceId).block();

        Assertions.assertThat(dto).isEqualTo(dto2);

        // wait for cache to expire
        Mono.delay(Duration.ofMillis(200)).block();

        // third call, should call real method again
        var dto3 = service.get2(id, workspaceId).block();

        Assertions.assertThat(dto).isNotEqualTo(dto3);
    }

    @Test
    void testCachePut__whenCachePut__shouldUpdateCache2(CachedService service) {

        String id = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();

        // first call, should call real method
        var dto = service.get2(id, workspaceId).block();

        // second call, should return cached value
        var dto2 = service.get2(id, workspaceId).block();

        Assertions.assertThat(dto).isEqualTo(dto2);

        // update value
        var updatedDto = new CachedService.DTO(id, workspaceId, UUID.randomUUID().toString());
        updatedDto = service.update2(id, workspaceId, updatedDto).block();

        // third call, should return updated value
        var dto3 = service.get2(id, workspaceId).block();

        Assertions.assertThat(updatedDto).isEqualTo(dto3);
    }

    @Test
    void testCacheEvict__whenCacheEvict__shouldRemoveCache2(CachedService service) {

        String id = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();

        // first call, should call real method
        var dto = service.get2(id, workspaceId).block();

        // second call, should return cached value
        var dto2 = service.get2(id, workspaceId).block();

        Assertions.assertThat(dto).isEqualTo(dto2);

        // evict cache
        service.evict2(id, workspaceId).block();

        // third call, should call real method again
        var dto3 = service.get2(id, workspaceId).block();

        Assertions.assertThat(dto).isNotEqualTo(dto3);
    }

    //Test error handling

    @Test
    void testCacheable__whenKeyInvalidExpression__shouldIgnoreCache(CachedService service) {

        String id = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();

        // first call, should call real method
        var dto = service.getWithKeyInvalidExpression(id, workspaceId);

        // second call, should return cached value
        var dto2 = service.getWithKeyInvalidExpression(id, workspaceId);

        Assertions.assertThat(dto).isNotEqualTo(dto2);
    }

    @Test
    void testCacheable__whenAnExceptionHappens__shouldIgnoreCacheAndPropagateIt(CachedService service) {

        String id = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();

        // first call, should call real method
        Assertions.assertThatThrownBy(() -> service.getWithException(id, workspaceId))
                .isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    void testCacheable__whenKeyInvalidExpression__shouldIgnoreCache2(CachedService service) {

        String id = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();

        // first call, should call real method
        var dto = service.get2WithInvalidKeyExpression(id, workspaceId).block();

        // second call, should return cached value
        var dto2 = service.get2WithInvalidKeyExpression(id, workspaceId).block();

        Assertions.assertThat(dto).isNotEqualTo(dto2);
    }

    @Test
    void testCacheable__whenAnExceptionHappens__shouldIgnoreCacheAndPropagateIt2(CachedService service) {

        String id = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();

        // first call, should call real method
        Assertions.assertThatThrownBy(() -> service.get2WithException(id, workspaceId).block())
                .isInstanceOf(IndexOutOfBoundsException.class);
    }

    // Test collection

    @Test
    void testCacheable__whenCacheableCollection__shouldCallRealMethodAgain(CachedService service) {

        String id = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();

        // first call, should call real method
        var dto = service.getCollection(id, workspaceId);

        // second call, should return cached value
        var dto2 = service.getCollection(id, workspaceId);

        Assertions.assertThat(dto).isEqualTo(dto2);
    }

    @Test
    void testCacheable__whenCacheableCollection__shouldCallRealMethodAgain2(CachedService service) {

        String id = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();

        // first call, should call real method
        var dto = service.getCollection2(id, workspaceId).block();

        // second call, should return cached value
        var dto2 = service.getCollection2(id, workspaceId).block();

        Assertions.assertThat(dto).isEqualTo(dto2);
    }

    // Test Flux

    @Test
    void testCacheable__whenCacheableFlux__shouldCallRealMethodAgain(CachedService service) {

        String id = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();

        // first call, should call real method
        var dto = service.getFlux(id, workspaceId).collectList().block();

        Mono.delay(Duration.ofMillis(50)).block();

        // second call, should return cached value
        var dto2 = service.getFlux(id, workspaceId).collectList().block();

        Assertions.assertThat(dto).isEqualTo(dto2);

        Mono.delay(Duration.ofMillis(500)).block();

        // third call, should call real method again
        var dto3 = service.getFlux(id, workspaceId).collectList().block();

        Assertions.assertThat(dto).isNotEqualTo(dto3);
    }

    @Test
    void testCacheable__whenCacheableFlux__shouldCallRealMethodAgain2(CachedService service) {

        String id = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();

        // first call, should call real method
        var dto = service.getFlux2(id, workspaceId).collectList().block();

        Mono.delay(Duration.ofMillis(50)).block();

        // second call, should return cached value
        var dto2 = service.getFlux2(id, workspaceId).collectList().block();

        Assertions.assertThat(dto).isEqualTo(dto2);

        Mono.delay(Duration.ofMillis(500)).block();

        // third call, should call real method again
        var dto3 = service.getFlux(id, workspaceId).collectList().block();

        Assertions.assertThat(dto).isNotEqualTo(dto3);
    }

}