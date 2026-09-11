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
import io.vavr.Function3;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.TestUtils.waitForMillis;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
@Slf4j
class CacheManagerTest {

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer MYSQL = MySQLContainerUtils.newMySQLContainer();
    private final GenericContainer<?> ZOOKEEPER_CONTAINER = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer CLICKHOUSE = ClickHouseContainerUtils.newClickHouseContainer(ZOOKEEPER_CONTAINER);

    private static final long CACHE_ASYNC_WAIT_MILLIS = 50;

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

    // Non-reactive tests

    @Test
    void testCacheable__whenCacheableExpire__shouldCallRealMethodAgain(CachedService service) {
        var id = UUID.randomUUID().toString();
        var workspaceId = UUID.randomUUID().toString();
        // first call, should call real method
        var dto = service.get(id, workspaceId);

        assertThat(dto).isNotNull();

        // wait for the cache put async to complete
        waitForMillis(CACHE_ASYNC_WAIT_MILLIS);

        // second call, should return cached value
        var dto2 = service.get(id, workspaceId);

        assertThat(dto).isEqualTo(dto2);

        // wait for cache to expire
        waitForMillis(1000);

        // third call, should call real method again
        var dto3 = service.get(id, workspaceId);

        assertThat(dto).isNotEqualTo(dto3);
    }

    @Test
    void testCachePut__whenCachePut__shouldUpdateCache(CachedService service) {
        var id = UUID.randomUUID().toString();
        var workspaceId = UUID.randomUUID().toString();
        // first call, should call real method
        var dto = service.get(id, workspaceId);

        assertThat(dto).isNotNull();

        // wait for the cache put async to complete
        waitForMillis(CACHE_ASYNC_WAIT_MILLIS);

        // second call, should return cached value
        var dto2 = service.get(id, workspaceId);

        assertThat(dto).isEqualTo(dto2);

        // update value
        var dtoUpdate = new CachedService.DTO(id, workspaceId, UUID.randomUUID().toString());
        var updatedDto = service.update(id, workspaceId, dtoUpdate);

        // wait for the cache put async to complete
        waitForMillis(CACHE_ASYNC_WAIT_MILLIS);

        // third call, should return updated value
        var dto3 = service.get(id, workspaceId);

        assertThat(updatedDto).isEqualTo(dto3);
    }

    @Test
    void testCacheEvict__whenCacheEvict__shouldRemoveCache(CachedService service) {
        var id = UUID.randomUUID().toString();
        var workspaceId = UUID.randomUUID().toString();
        // first call, should call real method
        var dto = service.get(id, workspaceId);

        assertThat(dto).isNotNull();

        // wait for the cache put async to complete
        waitForMillis(CACHE_ASYNC_WAIT_MILLIS);

        // second call, should return cached value
        var dto2 = service.get(id, workspaceId);

        assertThat(dto).isEqualTo(dto2);

        // evict cache
        service.evict(id, workspaceId);

        // wait for the cache evict async to complete
        waitForMillis(CACHE_ASYNC_WAIT_MILLIS);

        // third call, should call real method again
        var dto3 = service.get(id, workspaceId);

        assertThat(dto).isNotEqualTo(dto3);
    }

    // Reactive tests (Mono)

    @Test
    void testCacheable__whenCacheableExpire__shouldCallRealMethodAgain2(CachedService service) {
        var id = UUID.randomUUID().toString();
        var workspaceId = UUID.randomUUID().toString();
        // first call, should call real method
        var dto = service.get2(id, workspaceId).block();

        assertThat(dto).isNotNull();

        // second call, should return cached value
        var dto2 = service.get2(id, workspaceId).block();

        assertThat(dto).isEqualTo(dto2);

        // wait for cache to expire
        waitForMillis(200);

        // third call, should call real method again
        var dto3 = service.get2(id, workspaceId).block();

        assertThat(dto).isNotEqualTo(dto3);
    }

    @Test
    void testCachePut__whenCachePut__shouldUpdateCache2(CachedService service) {
        var id = UUID.randomUUID().toString();
        var workspaceId = UUID.randomUUID().toString();
        // first call, should call real method
        var dto = service.get2(id, workspaceId).block();

        assertThat(dto).isNotNull();

        // second call, should return cached value
        var dto2 = service.get2(id, workspaceId).block();

        assertThat(dto).isEqualTo(dto2);

        // update value
        var updatedDto = new CachedService.DTO(id, workspaceId, UUID.randomUUID().toString());
        updatedDto = service.update2(id, workspaceId, updatedDto).block();

        // third call, should return updated value
        var dto3 = service.get2(id, workspaceId).block();

        assertThat(updatedDto).isEqualTo(dto3);
    }

    @Test
    void testCacheEvict__whenCacheEvict__shouldRemoveCache2(CachedService service) {
        var id = UUID.randomUUID().toString();
        var workspaceId = UUID.randomUUID().toString();
        // first call, should call real method
        var dto = service.get2(id, workspaceId).block();

        assertThat(dto).isNotNull();

        // second call, should return cached value
        var dto2 = service.get2(id, workspaceId).block();

        assertThat(dto).isEqualTo(dto2);

        // evict cache
        service.evict2(id, workspaceId).block();

        // third call, should call real method again
        var dto3 = service.get2(id, workspaceId).block();

        assertThat(dto).isNotEqualTo(dto3);
    }

    @Test
    void testCacheEvict__whenCacheEvictWithValue__shouldRemoveCacheAndReturnValue2(CachedService service) {
        var id = UUID.randomUUID().toString();
        var workspaceId = UUID.randomUUID().toString();
        // first call, should call real method
        var dto = service.get2(id, workspaceId).block();

        assertThat(dto).isNotNull();

        // second call, should return cached value
        var dto2 = service.get2(id, workspaceId).block();

        assertThat(dto).isEqualTo(dto2);

        // evict cache and get return value
        var evictResult = service.evict2WithValue(id, workspaceId).block();

        // verify evict method returned a value
        assertThat(evictResult).isNotNull();
        assertThat(evictResult.id()).isEqualTo(id);
        assertThat(evictResult.workspaceId()).isEqualTo(workspaceId);

        // third call, should call real method again (cache was evicted)
        var dto3 = service.get2(id, workspaceId).block();

        assertThat(dto).isNotEqualTo(dto3);
    }

    //Test error handling

    @Test
    void testCacheable__whenKeyInvalidExpression__shouldIgnoreCache(CachedService service) {
        var id = UUID.randomUUID().toString();
        var workspaceId = UUID.randomUUID().toString();
        // first call, should call real method
        var dto = service.getWithKeyInvalidExpression(id, workspaceId);

        assertThat(dto).isNotNull();

        // wait like if there was cache put async to complete
        waitForMillis(CACHE_ASYNC_WAIT_MILLIS);

        // second call, should call real method again due to invalid key expression
        var dto2 = service.getWithKeyInvalidExpression(id, workspaceId);

        assertThat(dto).isNotEqualTo(dto2);
    }

    @Test
    void testCacheable__whenAnExceptionHappens__shouldIgnoreCacheAndPropagateIt(CachedService service) {
        var id = UUID.randomUUID().toString();
        var workspaceId = UUID.randomUUID().toString();
        // first call, should call real method
        assertThatThrownBy(() -> service.getWithException(id, workspaceId))
                .isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    void testCachePut__whenAnExceptionHappens__shouldIgnoreCacheAndPropagateIt(CachedService service) {
        var id = UUID.randomUUID().toString();
        var workspaceId = UUID.randomUUID().toString();
        var dto = new CachedService.DTO(id, workspaceId, UUID.randomUUID().toString());
        // should propagate exception without caching
        assertThatThrownBy(() -> service.updateWithException(id, workspaceId, dto))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Simulate runtime exception on update");
    }

    @Test
    void testCacheEvict__whenAnExceptionHappens__shouldIgnoreCacheAndPropagateIt(CachedService service) {
        var id = UUID.randomUUID().toString();
        var workspaceId = UUID.randomUUID().toString();
        // should propagate exception without evicting cache
        assertThatThrownBy(() -> service.evictWithException(id, workspaceId))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("Simulate runtime exception on evict");
    }

    @Test
    void testCacheable__whenKeyInvalidExpression__shouldIgnoreCache2(CachedService service) {
        var id = UUID.randomUUID().toString();
        var workspaceId = UUID.randomUUID().toString();
        // first call, should call real method
        var dto = service.get2WithInvalidKeyExpression(id, workspaceId).block();

        assertThat(dto).isNotNull();

        // second call, should return cached value
        var dto2 = service.get2WithInvalidKeyExpression(id, workspaceId).block();

        assertThat(dto).isNotEqualTo(dto2);
    }

    @Test
    void testCacheable__whenAnExceptionHappens__shouldIgnoreCacheAndPropagateIt2(CachedService service) {
        var id = UUID.randomUUID().toString();
        var workspaceId = UUID.randomUUID().toString();
        // first call, should call real method
        assertThatThrownBy(() -> service.get2WithException(id, workspaceId).block())
                .isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    void testCacheable__whenFluxWithException__shouldIgnoreCacheAndPropagateIt(CachedService service) {
        var id = UUID.randomUUID().toString();
        var workspaceId = UUID.randomUUID().toString();
        // should propagate exception without caching
        assertThatThrownBy(() -> service.getFluxWithException(id, workspaceId).collectList().block())
                .isInstanceOf(IndexOutOfBoundsException.class)
                .hasMessageContaining("Simulate runtime exception in Flux");
    }

    @Test
    void testCachePut__whenAnExceptionHappens__shouldIgnoreCacheAndPropagateIt2(CachedService service) {
        var id = UUID.randomUUID().toString();
        var workspaceId = UUID.randomUUID().toString();
        var dto = new CachedService.DTO(id, workspaceId, UUID.randomUUID().toString());
        // should propagate exception without caching
        assertThatThrownBy(() -> service.update2WithException(id, workspaceId, dto).block())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Simulate runtime exception on update");
    }

    @Test
    void testCacheEvict__whenAnExceptionHappens__shouldIgnoreCacheAndPropagateIt2(CachedService service) {
        var id = UUID.randomUUID().toString();
        var workspaceId = UUID.randomUUID().toString();
        // should propagate exception without evicting cache
        assertThatThrownBy(() -> service.evict2WithException(id, workspaceId).block())
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("Simulate runtime exception on evict");
    }

    // Test collection

    @Test
    void testCacheable__whenCacheableCollection__shouldCallRealMethodAgain(CachedService service) {
        var id = UUID.randomUUID().toString();
        var workspaceId = UUID.randomUUID().toString();
        // first call, should call real method
        var dto = service.getCollection(id, workspaceId);

        assertThat(dto).hasSize(1);

        // wait for the cache put async to complete
        waitForMillis(CACHE_ASYNC_WAIT_MILLIS);

        // second call, should return cached value
        var dto2 = service.getCollection(id, workspaceId);

        assertThat(dto).containsExactlyElementsOf(dto2);
    }

    @Test
    void testCacheable__whenCacheableCollection__shouldCallRealMethodAgain2(CachedService service) {
        var id = UUID.randomUUID().toString();
        var workspaceId = UUID.randomUUID().toString();
        // first call, should call real method
        var dto = service.getCollection2(id, workspaceId).block();

        assertThat(dto).hasSize(1);

        // second call, should return cached value
        var dto2 = service.getCollection2(id, workspaceId).block();

        assertThat(dto).containsExactlyElementsOf(dto2);
    }

    // Test Flux

    @Test
    void testCacheable__whenCacheableFlux__shouldCallRealMethodAgain(CachedService service) {
        var id = UUID.randomUUID().toString();
        var workspaceId = UUID.randomUUID().toString();
        // first call, should call real method
        var dto = service.getFlux(id, workspaceId).collectList().block();
        assertThat(dto).hasSize(2);

        waitForMillis(CACHE_ASYNC_WAIT_MILLIS);

        // second call, should return cached value
        var dto2 = service.getFlux(id, workspaceId).collectList().block();

        assertThat(dto).containsExactlyElementsOf(dto2);

        // wait for cache to expire
        waitForMillis(500);

        // third call, should call real method again
        var dto3 = service.getFlux(id, workspaceId).collectList().block();

        assertThat(dto).doesNotContainAnyElementsOf(dto3);
    }

    @Test
    void testCacheable__whenCacheableFlux__shouldCallRealMethodAgain2(CachedService service) {
        var id = UUID.randomUUID().toString();
        var workspaceId = UUID.randomUUID().toString();
        // first call, should call real method
        var dto = service.getFlux2(id, workspaceId).collectList().block();

        assertThat(dto).hasSize(2);

        waitForMillis(CACHE_ASYNC_WAIT_MILLIS);

        // second call, should return cached value
        var dto2 = service.getFlux2(id, workspaceId).collectList().block();

        assertThat(dto).containsExactlyElementsOf(dto2);

        // wait for cache to expire
        waitForMillis(500);

        // third call, should call real method again
        var dto3 = service.getFlux2(id, workspaceId).collectList().block();

        assertThat(dto).doesNotContainAnyElementsOf(dto3);
    }

    // Test null/empty values are not cached

    @Test
    void testCacheable__whenNullValue__shouldNotCache(CachedService service) {
        var id = UUID.randomUUID().toString();
        var workspaceId = UUID.randomUUID().toString();
        // first call, should call real method and return null
        var result1 = service.getWithNullValue(id, workspaceId);
        assertThat(result1).isNull();

        // wait for potential cache put async to complete
        waitForMillis(CACHE_ASYNC_WAIT_MILLIS);

        // second call, should call real method again (null was not cached)
        var result2 = service.getWithNullValue(id, workspaceId);
        assertThat(result2).isNull();
    }

    @Test
    void testCachePut__whenNullValue__shouldNotCache(CachedService service) {
        var id = UUID.randomUUID().toString();
        var workspaceId = UUID.randomUUID().toString();
        var dto = new CachedService.DTO(id, workspaceId, UUID.randomUUID().toString());

        // first, cache a valid value
        var cachedDto = service.update(id, workspaceId, dto);

        assertThat(cachedDto).isNotNull();

        // wait for cache put async to complete
        waitForMillis(CACHE_ASYNC_WAIT_MILLIS);

        // verify value is cached
        var fromCache = service.get(id, workspaceId);

        assertThat(fromCache).isEqualTo(cachedDto);

        // now try to put null value
        var nullResult = service.updateWithNullValue(id, workspaceId, dto);
        assertThat(nullResult).isNull();

        // wait for potential cache put async to complete
        waitForMillis(CACHE_ASYNC_WAIT_MILLIS);

        // verify cache still has the old value (null was not cached)
        var stillCached = service.get(id, workspaceId);

        assertThat(stillCached).isEqualTo(cachedDto);
    }

    @Test
    void testCacheable__whenEmptyMono__shouldNotCache(CachedService service) {
        var id = UUID.randomUUID().toString();
        var workspaceId = UUID.randomUUID().toString();
        // first call, should call real method and return empty
        var result1 = service.get2WithEmptyValue(id, workspaceId).block();

        assertThat(result1).isNull();

        // second call, should call real method again (empty was not cached)
        var result2 = service.get2WithEmptyValue(id, workspaceId).block();

        assertThat(result2).isNull();
    }

    @Test
    void testCachePut__whenEmptyMono__shouldNotCache(CachedService service) {
        var id = UUID.randomUUID().toString();
        var workspaceId = UUID.randomUUID().toString();
        var dto = new CachedService.DTO(id, workspaceId, UUID.randomUUID().toString());
        // first, cache a valid value
        var cachedDto = service.update2(id, workspaceId, dto).block();

        assertThat(cachedDto).isNotNull();

        // verify value is cached
        var fromCache = service.get2(id, workspaceId).block();

        assertThat(fromCache).isEqualTo(cachedDto);

        // now try to put empty value
        var emptyResult = service.update2WithEmptyValue(id, workspaceId, dto).block();

        assertThat(emptyResult).isNull();

        // verify cache still has the old value (empty was not cached)
        var stillCached = service.get2(id, workspaceId).block();

        assertThat(stillCached).isEqualTo(cachedDto);
    }

    @Test
    void testCacheable__whenEmptyFlux__shouldNotCache(CachedService service) {
        var id = UUID.randomUUID().toString();
        var workspaceId = UUID.randomUUID().toString();
        // first call, should call real method and return empty
        var result1 = service.getFluxWithEmptyValue(id, workspaceId).collectList().block();

        assertThat(result1).isEmpty();

        // wait for potential cache put async to complete
        waitForMillis(CACHE_ASYNC_WAIT_MILLIS);

        // second call, should call real method again (empty was not cached)
        var result2 = service.getFluxWithEmptyValue(id, workspaceId).collectList().block();

        assertThat(result2).isEmpty();
    }

    // Test nested cache annotation

    Stream<Function3<CachedService, String, String, List<CachedService.DTO>>> testCacheable__whenNestedCacheAnnotations__shouldWorkCorrectly() {
        return Stream.of(
                // Call 2-param overload
                (service, id, workspaceId) -> service.getOverloadedWithNestedCache(id, workspaceId),
                // Call 3-param overload with null
                (service, id, workspaceId) -> service.getOverloadedWithNestedCache(id, workspaceId, null));
    }

    /**
     * This test demonstrates that nested cache annotation, while not desirable, works fine.
     * Two overloaded methods both have @Cacheable and one delegates to the other.
     * There was a fix for an issue found originally in AutomationRuleEvaluatorService where:
     * <ul>
     *   <li>findAll(projectId, workspaceId) had @Cacheable and delegated to</li>
     *   <li>findAll(projectId, workspaceId, type) which also had @Cacheable</li>
     * </ul>
     * <p>
     */
    @ParameterizedTest
    @MethodSource
    void testCacheable__whenNestedCacheAnnotations__shouldWorkCorrectly(
            Function3<CachedService, String, String, List<CachedService.DTO>> methodCall,
            CachedService service) {
        var id = UUID.randomUUID().toString();
        var workspaceId = UUID.randomUUID().toString();
        // First call through 2-param method
        var result1 = service.getOverloadedWithNestedCache(id, workspaceId);
        assertThat(result1).hasSize(1);

        // wait for the cache put async to complete
        waitForMillis(CACHE_ASYNC_WAIT_MILLIS);

        // Second call using the parameterized method variant
        var result2 = methodCall.apply(service, id, workspaceId);

        assertThat(result1).containsExactlyElementsOf(result2);
    }

    @Test
    void testCacheable__whenHighConcurrencyNonReactiveMethods__shouldNotDeadlock(CachedService service)
            throws InterruptedException {
        var threadPoolSize = 50;
        var totalTasks = 100;
        var results = new ConcurrentHashMap<String, List<CachedService.DTO>>();
        var executor = Executors.newFixedThreadPool(threadPoolSize);
        try {
            IntStream.range(0, totalTasks).forEach(i -> executor.submit(() -> {
                var id = UUID.randomUUID().toString();
                var workspaceId = UUID.randomUUID().toString();
                var result = service.getOverloadedWithNestedCache(id, workspaceId);
                results.put(id, result);
            }));

            executor.shutdown();
            var completed = executor.awaitTermination(2, TimeUnit.SECONDS);

            // Verify all tasks completed without timeout or deadlock
            assertThat(completed).isTrue();
            assertThat(results).hasSize(totalTasks);
            // Verify all DTOs are not null
            assertThat(results.values().stream().flatMap(List::stream).toList())
                    .isNotEmpty()
                    .doesNotContainNull()
                    .hasSize(totalTasks);
        } finally {
            if (!executor.isTerminated()) {
                executor.shutdownNow();
            }
        }
    }
}
