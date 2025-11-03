package com.comet.opik.infrastructure.redis;

import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.lock.LockService;
import com.redis.testcontainers.RedisContainer;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.redisson.api.RedissonReactiveClient;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.AppContextConfig;
import static com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.CustomConfig;
import static com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class RedissonLockServiceIntegrationTest {

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer MYSQL = MySQLContainerUtils.newMySQLContainer();
    private final GenericContainer<?> ZOOKEEPER_CONTAINER = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer CLICKHOUSE = ClickHouseContainerUtils.newClickHouseContainer(ZOOKEEPER_CONTAINER);

    @RegisterApp
    private final TestDropwizardAppExtension APP;

    {
        Startables.deepStart(REDIS, MYSQL, CLICKHOUSE, ZOOKEEPER_CONTAINER).join();
        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(CLICKHOUSE,
                ClickHouseContainerUtils.DATABASE_NAME);

        APP = newTestDropwizardAppExtension(
                AppContextConfig.builder()
                        .jdbcUrl(MYSQL.getJdbcUrl())
                        .databaseAnalyticsFactory(databaseAnalyticsFactory)
                        .redisUrl(REDIS.getRedisURI())
                        .customConfigs(List.of(new CustomConfig("distributedLock.lockTimeoutMS", "100"),
                                new CustomConfig("distributedLock.ttlInSeconds", "1")))
                        .build());
    }

    @Test
    void testExecuteWithLock_AddIfAbsent_Mono(LockService lockService) {
        LockService.Lock lock = new LockService.Lock(UUID.randomUUID(), "test-lock");
        List<String> sharedList = new ArrayList<>();

        String[] valuesToAdd = {"A", "B", "C", "A", "B", "C", "A", "B", "C"};

        Flux<Void> actions = Flux.fromArray(valuesToAdd)
                .flatMap(value -> lockService.executeWithLock(lock, Mono.fromRunnable(() -> {
                    if (!sharedList.contains(value)) {
                        sharedList.add(value);
                    }
                })), 5)
                .thenMany(Flux.empty());

        StepVerifier.create(actions)
                .expectSubscription()
                .verifyComplete();

        // Verify that the list contains only unique values
        assertEquals(3, sharedList.size(), "The list should contain only unique values");
        assertTrue(sharedList.contains("A"));
        assertTrue(sharedList.contains("B"));
        assertTrue(sharedList.contains("C"));
    }

    @Test
    void testExecuteWithLock_AddIfAbsent_Flux(LockService lockService) {
        LockService.Lock lock = new LockService.Lock(UUID.randomUUID(), "test-lock");
        List<String> sharedList = new ArrayList<>();

        Flux<String> valuesToAdd = Flux.just("A", "B", "C", "A", "B", "C", "A", "B", "C");

        Flux<Void> actions = lockService.executeWithLock(lock, valuesToAdd
                .flatMap(value -> {

                    Mono<Void> objectMono = Mono.fromRunnable(() -> {
                        if (!sharedList.contains(value)) {
                            sharedList.add(value);
                        }
                    });

                    return objectMono.subscribeOn(Schedulers.parallel());
                }))
                .repeat(5);

        StepVerifier.create(actions)
                .expectSubscription()
                .verifyComplete();

        // Verify that the list contains only unique values
        assertEquals(3, sharedList.size(), "The list should contain only unique values");
        assertTrue(sharedList.contains("A"));
        assertTrue(sharedList.contains("B"));
        assertTrue(sharedList.contains("C"));
    }

    @Test
    void testExecuteWithLock_LockShouldHaveBeenEvicted(LockService lockService, RedissonReactiveClient redisClient) {
        LockService.Lock lock = new LockService.Lock(UUID.randomUUID(), "test-lock");
        List<String> sharedList = new ArrayList<>();

        lockService.executeWithLock(lock, Mono.delay(Duration.ofMillis(100)).then(Mono.fromCallable(() -> {
            sharedList.add("A");
            return true;
        }))).block();

        Mono.delay(Duration.ofMillis(1500)).block();

        StepVerifier.create(redisClient.getBucket(lock.key()).isExists())
                .assertNext(data -> assertThat(data).isFalse())
                .verifyComplete();

        lockService.executeWithLock(lock, Mono.delay(Duration.ofMillis(100)).then(Mono.fromCallable(() -> {
            sharedList.add("B");
            return true;
        }))).block();

        assertTrue(sharedList.contains("A"));
        assertTrue(sharedList.contains("B"));

        Mono.delay(Duration.ofSeconds(1)).block();

        StepVerifier.create(redisClient.getBucket(lock.key()).isExists())
                .assertNext(data -> assertThat(data).isFalse())
                .verifyComplete();
    }

    @Test
    void testLockUsingToken_HappyPath(LockService lockService, RedissonReactiveClient redisClient) {
        // Create a unique lock
        LockService.Lock lock = new LockService.Lock(UUID.randomUUID(), "token-lock");

        // Verify lock acquisition succeeds
        StepVerifier.create(lockService.lockUsingToken(lock, Duration.ofSeconds(5)))
                .assertNext(acquired -> assertThat(acquired).isTrue())
                .verifyComplete();

        // Verify the lock exists in Redis
        StepVerifier.create(redisClient.getBucket(lock.key()).isExists())
                .assertNext(exists -> assertThat(exists).isTrue())
                .verifyComplete();

        // Verify attempting to acquire the same lock fails
        StepVerifier.create(lockService.lockUsingToken(lock, Duration.ofSeconds(5)))
                .assertNext(acquired -> assertThat(acquired).isFalse())
                .verifyComplete();

        // Unlock the lock
        StepVerifier.create(lockService.unlockUsingToken(lock))
                .verifyComplete();

        // Verify the lock no longer exists
        StepVerifier.create(redisClient.getBucket(lock.key()).isExists())
                .assertNext(exists -> assertThat(exists).isFalse())
                .verifyComplete();

        // Verify we can acquire the lock again
        StepVerifier.create(lockService.lockUsingToken(lock, Duration.ofSeconds(5)))
                .assertNext(acquired -> assertThat(acquired).isTrue())
                .verifyComplete();
    }

    @Test
    void testLockUsingToken_Eviction(LockService lockService, RedissonReactiveClient redisClient) {
        // Create a unique lock
        LockService.Lock lock = new LockService.Lock(UUID.randomUUID(), "token-lock-eviction");

        // Acquire the lock with a short timeout
        StepVerifier.create(lockService.lockUsingToken(lock, Duration.ofMillis(500)))
                .assertNext(acquired -> assertThat(acquired).isTrue())
                .verifyComplete();

        // Verify the lock exists
        StepVerifier.create(redisClient.getBucket(lock.key()).isExists())
                .assertNext(exists -> assertThat(exists).isTrue())
                .verifyComplete();

        // Wait for the lock to expire
        Mono.delay(Duration.ofMillis(700)).block();

        // Verify the lock has been evicted
        StepVerifier.create(redisClient.getBucket(lock.key()).isExists())
                .assertNext(exists -> assertThat(exists).isFalse())
                .verifyComplete();

        // Verify we can acquire the lock again
        StepVerifier.create(lockService.lockUsingToken(lock, Duration.ofSeconds(5)))
                .assertNext(acquired -> assertThat(acquired).isTrue())
                .verifyComplete();
    }

    @Test
    void testBestEffortLock_HappyPath(LockService lockService) {
        // Create a unique lock
        LockService.Lock lock = new LockService.Lock(UUID.randomUUID(), "best-effort-lock");
        List<String> results = new ArrayList<>();

        // Define the main action and fallback action
        Mono<String> mainAction = Mono.fromCallable(() -> {
            results.add("main-action");
            return "main-result";
        });

        Mono<Void> fallbackAction = Mono.fromCallable(() -> {
            results.add("fallback-action");
            return null;
        });

        // Execute best effort lock - should succeed
        StepVerifier.create(lockService.bestEffortLock(
                lock,
                mainAction,
                fallbackAction,
                Duration.ofSeconds(1), // action timeout
                Duration.ofSeconds(1) // lock wait time
        ))
                .expectNext("main-result")
                .verifyComplete();

        // Verify the main action was executed
        assertEquals(1, results.size());
        assertEquals("main-action", results.getFirst());
    }

    @Test
    void testBestEffortLock_FallbackWhenLockUnavailable(LockService lockService, RedissonReactiveClient redisClient) {
        // Create two locks with the same key to simulate contention
        LockService.Lock lock = new LockService.Lock(UUID.randomUUID(), "best-effort-lock-fallback");
        List<String> results = new ArrayList<>();

        // Define actions for the first lock holder
        Mono<String> longRunningAction = Mono.fromCallable(() -> {
            // Simulate a long-running operation that holds the lock
            Mono.delay(Duration.ofMillis(500)).block();
            return "long-running-completed";
        });

        // Define actions for the second lock attempt
        Mono<String> mainAction = Mono.fromCallable(() -> {
            results.add("main-action");
            return "main-result";
        });

        Mono<Void> fallbackAction = Mono.fromCallable(() -> {
            results.add("fallback-action");
            return null;
        });

        // First, acquire the lock with a long-running action (in a separate thread)
        Mono<String> firstLockOperation = Mono.defer(() -> lockService.bestEffortLock(lock, longRunningAction,
                Mono.empty(),
                Duration.ofMillis(900), // action timeout
                Duration.ofMillis(200) // lock wait time
        ));

        // Start the first operation in a separate thread
        Mono<String> secondLockOperation = Mono.defer(() -> lockService.bestEffortLock(lock, mainAction,
                fallbackAction,
                Duration.ofMillis(900), // action timeout
                Duration.ofMillis(200) // lock wait time
        ));

        // Give the first operation time to acquire the lock
        Schedulers.boundedElastic().schedule(firstLockOperation::subscribe);

        Mono.delay(Duration.ofMillis(100)).block();

        // Now try to acquire the same lock with bestEffortLock
        StepVerifier.create(secondLockOperation)
                .verifyComplete();

        // Verify the fallback action was executed
        assertEquals(1, results.size());
        assertEquals("fallback-action", results.getFirst());
    }
}
