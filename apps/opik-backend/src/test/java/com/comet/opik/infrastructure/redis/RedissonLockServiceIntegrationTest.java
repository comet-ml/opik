package com.comet.opik.infrastructure.redis;

import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.lock.LockService;
import com.redis.testcontainers.RedisContainer;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.RandomStringUtils;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.AppContextConfig;
import static com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.CustomConfig;
import static com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link RedissonLockService} against a real Redis container.
 * <p>
 * Covers happy paths and every branching path that can be exercised against real Redis:
 * action terminal signals (success / empty / error / cancellation), {@code holdUntilExpiry}
 * lease semantics, concurrent contention, custom expire durations, key eviction, and the
 * regression scenario for the production counter-underflow bug.
 * <p>
 * Scenarios that require fault injection inside {@code semaphore.expire(...)} or
 * deterministic control over {@code RedisException} propagation (the retry path in
 * {@code acquireLock} and the {@code onErrorResume} path in {@code bestEffortLock}) live in
 * {@link RedissonLockServiceTest} where the semaphore is mocked.
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class RedissonLockServiceIntegrationTest {

    private static final int RANDOM_STRING_LENGTH = 32;
    private static final long RELEASE_PROPAGATION_MS = 200;
    private static final Duration AWAIT_ACTION_START = Duration.ofSeconds(5);

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer MYSQL = MySQLContainerUtils.newMySQLContainer();
    private final GenericContainer<?> ZOOKEEPER_CONTAINER = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer CLICKHOUSE = ClickHouseContainerUtils.newClickHouseContainer(ZOOKEEPER_CONTAINER);

    @RegisterApp
    private final TestDropwizardAppExtension app;

    {
        Startables.deepStart(REDIS, MYSQL, CLICKHOUSE, ZOOKEEPER_CONTAINER).join();
        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(CLICKHOUSE,
                ClickHouseContainerUtils.DATABASE_NAME);

        app = newTestDropwizardAppExtension(
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

    /**
     * Cancellation path of the {@code doFinally(release)} on {@code executeWithLock(Mono)}'s
     * held-action chain. Symmetric to {@link #testBestEffortLock_ReleasesPermitAfterCancellation}
     * but for the non-best-effort surface.
     */
    @Test
    void testExecuteWithLock_ReleasesPermitAfterCancellation(LockService lockService) {
        var lock = new LockService.Lock(UUID.randomUUID(),
                "lockName-%s".formatted(RandomStringUtils.secure().nextAlphanumeric(RANDOM_STRING_LENGTH)));
        var actionStarted = new AtomicBoolean(false);

        var slowAction = Mono.defer(() -> {
            actionStarted.set(true);
            return Mono.delay(Duration.ofSeconds(10)).thenReturn("never-completes");
        });

        var disposable = lockService.executeWithLock(lock, slowAction).subscribe();

        Awaitility.await().atMost(AWAIT_ACTION_START).untilTrue(actionStarted);

        disposable.dispose();

        // Give the doFinally a moment to run tryRelease after the cancel signal propagates.
        TestUtils.waitForMillis(RELEASE_PROPAGATION_MS);

        StepVerifier.create(lockService.executeWithLock(lock, Mono.just("after-cancel")))
                .expectNext("after-cancel")
                .verifyComplete();
    }

    /**
     * Stream-error release path on {@code executeWithLock(Flux)}: {@code runStream}'s
     * {@code doFinally} must fire on {@code ON_ERROR}, releasing the permit so the next
     * acquire is not blocked. Pins the Flux variant of the action-error contract.
     */
    @Test
    void testExecuteWithLock_Flux_ReleasesPermitAfterStreamError(LockService lockService) {
        var lock = new LockService.Lock(UUID.randomUUID(),
                "lockName-%s".formatted(RandomStringUtils.secure().nextAlphanumeric(RANDOM_STRING_LENGTH)));

        StepVerifier.create(lockService.executeWithLock(
                lock,
                Flux.error(new RuntimeException("stream failed"))))
                .expectError(RuntimeException.class)
                .verify();

        StepVerifier.create(lockService.executeWithLock(lock, Flux.just("after-error")))
                .expectNext("after-error")
                .verifyComplete();
    }

    /**
     * Mirrors {@link #testBestEffortLock_ReleasesPermitAfterActionTerminal} for
     * {@code executeWithLock(Mono)}: the release path here lives inside
     * {@code runAction(...).doFinally(release)}. A regression that removed that
     * {@code doFinally} would let happy-path tests pass but break release on action error.
     */
    @Test
    void testExecuteWithLock_ReleasesPermitAfterActionError(LockService lockService) {
        var lock = new LockService.Lock(UUID.randomUUID(),
                "lockName-%s".formatted(RandomStringUtils.secure().nextAlphanumeric(RANDOM_STRING_LENGTH)));

        StepVerifier.create(lockService.executeWithLock(
                lock,
                Mono.fromCallable(() -> {
                    throw new RuntimeException("action failed");
                })))
                .expectError(RuntimeException.class)
                .verify();

        // Subsequent acquire must succeed — the lock was released even on the error path.
        StepVerifier.create(lockService.executeWithLock(
                lock,
                Mono.just("after-error")))
                .expectNext("after-error")
                .verifyComplete();
    }

    /**
     * {@code executeWithLockCustomExpire} applies {@code max(duration, defaultLockTTL)} as
     * the underlying key TTL. With a duration larger than {@code ttlInSeconds=1}, the key
     * must still be live well past the 1s default after the action completes.
     */
    @Test
    void testExecuteWithLockCustomExpire_RespectsCustomDuration(
            LockService lockService, RedissonReactiveClient redisClient) {
        var lock = new LockService.Lock(UUID.randomUUID(),
                "lockName-%s".formatted(RandomStringUtils.secure().nextAlphanumeric(RANDOM_STRING_LENGTH)));
        var customDuration = Duration.ofSeconds(3);

        lockService.executeWithLockCustomExpire(
                lock,
                Mono.just("done"),
                customDuration).block();

        // After action and release, the counter key keeps the TTL set by expire(...).
        // With ttlInSeconds=1 in test config, a remaining TTL well past 1s proves the
        // custom duration was used and not the default.
        StepVerifier.create(redisClient.getBucket(lock.key()).remainTimeToLive())
                .assertNext(ttl -> assertThat(ttl)
                        .as("custom expire duration must shape the underlying key TTL")
                        .isGreaterThan(1500L))
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

        // Execute the best effort lock - should succeed
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
    void testBestEffortLock_HoldUntilExpiry(LockService lockService) {
        LockService.Lock lock = new LockService.Lock(UUID.randomUUID(), "best-effort-hold-until-expiry");
        List<String> results = new ArrayList<>();

        Mono<String> mainAction = Mono.fromCallable(() -> {
            results.add("first-action");
            return "first-result";
        });

        Mono<Void> fallbackAction = Mono.fromCallable(() -> {
            results.add("fallback-action");
            return null;
        });

        // Acquire lock with holdUntilExpiry=true, short TTL (1s)
        StepVerifier.create(lockService.bestEffortLock(
                lock,
                mainAction,
                fallbackAction,
                Duration.ofSeconds(1), // lock TTL
                Duration.ofMillis(200), // lock wait time
                true // holdUntilExpiry
        ))
                .expectNext("first-result")
                .verifyComplete();

        // Action completed, but lock should still be held — second attempt should hit fallback
        Mono<String> secondAction = Mono.fromCallable(() -> {
            results.add("second-action");
            return "second-result";
        });

        StepVerifier.create(lockService.bestEffortLock(
                lock,
                secondAction,
                fallbackAction,
                Duration.ofSeconds(1),
                Duration.ofMillis(200)))
                .verifyComplete();

        // First action ran, second hit fallback because lock was still held
        assertThat(results).containsExactly("first-action", "fallback-action");
    }

    @Test
    void testBestEffortLock_FallbackWhenLockUnavailable(LockService lockService) {
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

    /**
     * Regression guard for the production failure where bestEffortLock with
     * holdUntilExpiry=false drove the underlying Redis counter to -1 across replicas,
     * blocking subsequent job cycles until the semaphore key fully expired.
     */
    @Test
    void testBestEffortLock_DoesNotDriveCounterNegative(LockService lockService, RedissonReactiveClient redisClient) {
        var lock = new LockService.Lock(UUID.randomUUID(), "best-effort-lock-permit-underflow");
        var semaphore = redisClient.getPermitExpirableSemaphore(lock.key());

        var longLeaseSeconds = 30;
        // Inject the divergent state: temporarily raise the limit to 2 and acquire both
        // with a long lease so both permits remain valid for the rest of the test. This
        // mirrors the production shape where the semaphore ends up holding more permits
        // than the configured limit and the next bestEffortLock cycle observes that
        // divergence.
        assertThat(semaphore.trySetPermits(2).block()).isTrue();
        var permit1 = semaphore.tryAcquire(0L, longLeaseSeconds, TimeUnit.SECONDS).block();
        var permit2 = semaphore.tryAcquire(0L, longLeaseSeconds, TimeUnit.SECONDS).block();
        assertThat(permit1).isNotNull();
        assertThat(permit2).isNotNull();
        assertEquals(0, semaphore.availablePermits().block());

        // Run a best-effort acquire while the divergent state is in place. The fix
        // (trySetPermits) leaves the counter at 0; the pre-fix (setPermits) drops it to -1.
        StepVerifier.create(lockService.bestEffortLock(
                lock,
                Mono.fromCallable(() -> "no-op-action"),
                Mono.empty(),
                Duration.ofSeconds(1),
                Duration.ofMillis(100)))
                .verifyComplete();

        // availablePermits() opportunistically cleans up expired permits; with the long
        // lease above, both injected permits are still valid, so it returns the raw
        // counter value.
        assertThat(semaphore.availablePermits().block())
                .as("counter must not underflow when bestEffortLock runs against a divergent state")
                .isGreaterThanOrEqualTo(0);

        semaphore.release(permit1).block();
        semaphore.release(permit2).block();
    }

    static Stream<Arguments> testBestEffortLock_ReleasesPermitAfterActionTerminal() {
        return Stream.of(
                Arguments.of("success", Mono.just("first-success")),
                Arguments.of("empty-completion", Mono.empty()),
                Arguments.of("error", Mono.error(new RuntimeException("first-error"))));
    }

    /**
     * Pins the release contract on every action terminal Reactor can deliver via the
     * action itself: {@code ON_COMPLETE} (success and empty completion) and {@code ON_ERROR}.
     * After the first call terminates, a follow-up call against the same lock must acquire
     * inside {@code lockWaitTime} — if it didn't, the outer {@code doFinally} in
     * {@code bestEffortLock} regressed. Cancellation is covered separately because it
     * requires manual subscription/dispose.
     */
    @ParameterizedTest(name = "permit released after action {0}")
    @MethodSource
    void testBestEffortLock_ReleasesPermitAfterActionTerminal(
            String label, Mono<String> firstAction, LockService lockService) {
        var lock = new LockService.Lock(UUID.randomUUID(),
                "lockName-%s".formatted(RandomStringUtils.secure().nextAlphanumeric(RANDOM_STRING_LENGTH)));
        var expectedValue = "after-%s".formatted(label);

        // Drive the first cycle to its terminal. Errors are expected for the error scenario,
        // so swallow them — the contract under test is what happens to the permit AFTER
        // the terminal, not the terminal itself.
        lockService.bestEffortLock(lock, firstAction, Mono.empty(),
                Duration.ofSeconds(5), Duration.ofMillis(100))
                .onErrorResume(throwable -> Mono.empty())
                .block();

        // A regression that leaves the permit unreleased would force the second call's
        // tryAcquire to wait its lockWaitTime and then run the empty fallback, so the
        // StepVerifier would observe no value rather than the expected one.
        StepVerifier.create(lockService.bestEffortLock(
                lock,
                Mono.just(expectedValue),
                Mono.empty(),
                Duration.ofSeconds(5),
                Duration.ofMillis(100)))
                .expectNext(expectedValue)
                .verifyComplete();
    }

    /**
     * Cancellation path of the outer {@code doFinally} in {@code bestEffortLock}. Quartz
     * interrupts in production dispose the subscription mid-action; the permit must be
     * released so the next job cycle is not blocked on lockWaitTime.
     */
    @Test
    void testBestEffortLock_ReleasesPermitAfterCancellation(LockService lockService) {
        var lock = new LockService.Lock(UUID.randomUUID(),
                "lockName-%s".formatted(RandomStringUtils.secure().nextAlphanumeric(RANDOM_STRING_LENGTH)));
        var actionStarted = new AtomicBoolean(false);

        var slowAction = Mono.defer(() -> {
            actionStarted.set(true);
            return Mono.delay(Duration.ofSeconds(10)).thenReturn("never-completes");
        });

        var disposable = lockService.bestEffortLock(
                lock, slowAction, Mono.empty(),
                Duration.ofSeconds(30), Duration.ofMillis(100))
                .subscribe();

        Awaitility.await().atMost(AWAIT_ACTION_START).untilTrue(actionStarted);

        disposable.dispose();

        // Give the outer doFinally a moment to run tryRelease.
        TestUtils.waitForMillis(RELEASE_PROPAGATION_MS);

        StepVerifier.create(lockService.bestEffortLock(
                lock, Mono.just("after-cancel"), Mono.empty(),
                Duration.ofSeconds(5), Duration.ofMillis(100)))
                .expectNext("after-cancel")
                .verifyComplete();
    }

    /**
     * Mutual exclusion under real concurrency: when N callers fire {@code bestEffortLock}
     * in parallel, exactly one runs the main action and the other {@code N-1} run the
     * fallback. A regression in {@code trySetPermits}/{@code tryAcquire} or in mutual
     * exclusion semantics would let multiple mains slip through or block all callers.
     */
    @Test
    void testBestEffortLock_ConcurrentContentionExactlyOneAcquires(LockService lockService) {
        var lock = new LockService.Lock(UUID.randomUUID(),
                "lockName-%s".formatted(RandomStringUtils.secure().nextAlphanumeric(RANDOM_STRING_LENGTH)));
        int contenders = 10;
        var mainRuns = new AtomicInteger(0);
        var fallbackRuns = new AtomicInteger(0);

        // Winner holds the lock long enough that every other contender's 100ms wait elapses.
        var mainAction = Mono.defer(() -> {
            mainRuns.incrementAndGet();
            return Mono.delay(Duration.ofMillis(500)).thenReturn("won");
        });

        Mono<Void> fallback = Mono.fromRunnable(fallbackRuns::incrementAndGet);

        var contention = Flux.range(0, contenders)
                .flatMap(i -> lockService.bestEffortLock(
                        lock, mainAction, fallback,
                        Duration.ofSeconds(5), Duration.ofMillis(100)),
                        contenders);

        StepVerifier.create(contention)
                .expectNextCount(1)
                .verifyComplete();

        assertThat(mainRuns).hasValue(1);
        assertThat(fallbackRuns).hasValue(contenders - 1);
    }

    /**
     * Under {@code holdUntilExpiry=true}, the lock must stay held even when the action
     * itself fails — the contract is "hold until the lease elapses regardless of the action
     * terminal." A regression that released on error (e.g., dropping the
     * {@code !holdUntilExpiry} guard in the outer {@code doFinally}) would let the second
     * cycle acquire the main path instead of the fallback.
     */
    @Test
    void testBestEffortLock_HoldUntilExpiry_HoldsLockOnActionError(LockService lockService) {
        var lock = new LockService.Lock(UUID.randomUUID(),
                "lockName-%s".formatted(RandomStringUtils.secure().nextAlphanumeric(RANDOM_STRING_LENGTH)));
        var fallbackRan = new AtomicBoolean(false);

        StepVerifier.create(lockService.bestEffortLock(
                lock,
                Mono.error(new RuntimeException("first failed")),
                Mono.empty(),
                Duration.ofSeconds(2),
                Duration.ofMillis(100),
                true))
                .expectError(RuntimeException.class)
                .verify();

        // Second call within the lease must fall back: the lock is still held even though
        // the first action errored.
        StepVerifier.create(lockService.bestEffortLock(
                lock,
                Mono.just("should-not-run"),
                Mono.fromRunnable(() -> fallbackRan.set(true)),
                Duration.ofSeconds(2),
                Duration.ofMillis(100),
                true))
                .verifyComplete();

        assertThat(fallbackRan)
                .as("lock must stay held under holdUntilExpiry=true even after action error")
                .isTrue();
    }

    /**
     * The {@code holdUntilExpiry=true} contract has two halves: the lock stays held while
     * the lease is active (verified by {@link #testBestEffortLock_HoldUntilExpiry}) AND it
     * eventually becomes acquirable again when the lease elapses. This test pins the
     * second half — if lease eviction broke, no other test would notice.
     */
    @Test
    void testBestEffortLock_HoldUntilExpiry_ReleasesAfterLease(LockService lockService) {
        var lock = new LockService.Lock(UUID.randomUUID(),
                "lockName-%s".formatted(RandomStringUtils.secure().nextAlphanumeric(RANDOM_STRING_LENGTH)));
        var leaseDuration = Duration.ofSeconds(1);

        StepVerifier.create(lockService.bestEffortLock(
                lock, Mono.just("first"), Mono.empty(),
                leaseDuration, Duration.ofMillis(100), true))
                .expectNext("first")
                .verifyComplete();

        // Wait past the lease so the orphan permit's zset entry expires by score.
        TestUtils.waitForMillis(leaseDuration.plus(Duration.ofMillis(500)).toMillis());

        StepVerifier.create(lockService.bestEffortLock(
                lock, Mono.just("after-lease"), Mono.empty(),
                Duration.ofSeconds(5), Duration.ofMillis(500), true))
                .expectNext("after-lease")
                .verifyComplete();
    }

    /**
     * The outer {@code onErrorResume(RedisException.class, ...)} in {@code bestEffortLock}
     * only catches {@code RedisException}; a fallback that errors with anything else must
     * propagate to the caller rather than being silently swallowed.
     */
    @Test
    void testBestEffortLock_FailingFallbackPropagatesError(LockService lockService) {
        var lock = new LockService.Lock(UUID.randomUUID(),
                "lockName-%s".formatted(RandomStringUtils.secure().nextAlphanumeric(RANDOM_STRING_LENGTH)));
        var holdActionStarted = new AtomicBoolean(false);
        // Blocks the holder action until the test signals it to return.
        var releaseHold = new CountDownLatch(1);

        var holdAction = Mono.defer(() -> {
            holdActionStarted.set(true);
            return Mono.fromCallable(() -> {
                releaseHold.await();
                return "held";
            }).subscribeOn(Schedulers.boundedElastic());
        });

        var holder = lockService.bestEffortLock(
                lock, holdAction, Mono.empty(),
                Duration.ofSeconds(30), Duration.ofMillis(100))
                .subscribe();
        try {
            Awaitility.await().atMost(AWAIT_ACTION_START).untilTrue(holdActionStarted);

            // Lock unavailable → switchIfEmpty fires the failing fallback → error propagates
            // through the chain (the outer onErrorResume only catches RedisException).
            StepVerifier.create(lockService.bestEffortLock(
                    lock,
                    Mono.just("should-not-run"),
                    Mono.error(new IllegalStateException("fallback exploded")),
                    Duration.ofSeconds(5),
                    Duration.ofMillis(100)))
                    .expectError(IllegalStateException.class)
                    .verify();
        } finally {
            // Unblock the action and force cleanup; dispose() is idempotent.
            releaseHold.countDown();
            holder.dispose();
        }
    }
}
