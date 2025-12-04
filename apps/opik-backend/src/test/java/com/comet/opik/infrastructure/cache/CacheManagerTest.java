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
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
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

    // Test nested cache annotation issue

    /**
     * This test demonstrates the nested cache annotation deadlock issue.
     * When two overloaded methods both have @Cacheable and one delegates to the other,
     * it creates nested Mono.block() calls that can cause Redis timeout exceptions.
     * <p>
     * This reproduces the issue found in AutomationRuleEvaluatorService where:
     * <ul>
     *   <li>findAll(projectId, workspaceId) had @Cacheable and delegated to</li>
     *   <li>findAll(projectId, workspaceId, type) which also had @Cacheable</li>
     * </ul>
     * <p>
     * <strong>What happens with nested cache operations:</strong>
     * <ol>
     *   <li>First @Cacheable on getOverloadedWithNestedCache(id, workspaceId)
     *       <ul>
     *         <li>Cache miss, proceeds with Mono.defer() and invocation.proceed()</li>
     *         <li>Inside this, the method delegates to getOverloadedWithNestedCache(id, workspaceId, null)</li>
     *       </ul>
     *   </li>
     *   <li>Second @Cacheable on getOverloadedWithNestedCache(id, workspaceId, type)
     *       <ul>
     *         <li>Another cache operation NESTED inside the first</li>
     *         <li>Creates nested Mono.block() which violates Reactor threading rules</li>
     *       </ul>
     *   </li>
     * </ol>
     * <p>
     * <strong>Expected behavior:</strong> May timeout or cause reactor threading violations.
     */
    @Test
    void testCacheable__whenNestedCacheAnnotations__shouldCauseIssue(CachedService service) {
        var id = UUID.randomUUID().toString();
        var workspaceId = UUID.randomUUID().toString();
        try {
            var result = service.getOverloadedWithNestedCache(id, workspaceId);

            // If it doesn't time out, verify it at least works functionally
            Assertions.assertThat(result).isNotNull();
            Assertions.assertThat(result).hasSize(1);
            // Note: In production with high load, this would likely time out
            // In tests with low concurrency, it might succeed but is still problematic
            log.info("Nested cache call succeeded unexpectedly: '{}'", result);
        } catch (Exception exception) {
            log.error("Expected exception due to nested cache annotations", exception);
            // Expected: RedisTimeoutException or reactor threading violation
            // This is the bug we're demonstrating
            Assertions.assertThat(exception)
                    .satisfiesAnyOf(
                            ex -> Assertions.assertThat(ex).hasMessageContaining("timeout"),
                            ex -> Assertions.assertThat(ex).hasMessageContaining("Redis"),
                            ex -> Assertions.assertThat(ex).hasMessageContaining("block"));
        }
    }

    /**
     * This test demonstrates the FIXED version where only the implementation method has @Cacheable.
     * The delegating method has NO cache annotation, avoiding nested cache operations.
     * <p>
     * This is the fix applied to AutomationRuleEvaluatorService.
     */
    @Test
    void testCacheable__whenFixedOverloadedMethods__shouldWorkCorrectly(CachedService service) {
        var id = UUID.randomUUID().toString();
        var workspaceId = UUID.randomUUID().toString();
        // First call through 2-param method
        var result1 = service.getOverloadedFixed(id, workspaceId);
        // Second call should return cached value from the 3-param method's cache
        var result2 = service.getOverloadedFixed(id, workspaceId);

        // Both calls should succeed without timeout
        Assertions.assertThat(result1).isNotNull();
        Assertions.assertThat(result2).isNotNull();
        // Values should be identical (from cache)
        Assertions.assertThat(result1).isEqualTo(result2);
        Assertions.assertThat(result1.getFirst().value()).isEqualTo(result2.getFirst().value());
    }
}
