package com.comet.opik.infrastructure.redis;

import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.infrastructure.DistributedLockConfig;
import com.comet.opik.infrastructure.lock.LockService;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RPermitExpirableSemaphoreReactive;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.api.options.CommonOptions;
import org.redisson.client.RedisException;
import reactor.core.Exceptions;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RedissonLockService} covering paths that cannot be reliably
 * exercised against a real Redis container:
 * <ul>
 *   <li>{@code semaphore.expire(...)} failure / cancellation — the orphan-prevention
 *       inside {@code expire()}'s inner {@code doFinally};</li>
 *   <li>retry contract in {@code acquireLock} when the underlying semaphore throws
 *       {@code RedisException};</li>
 *   <li>{@code bestEffortLock}'s outer {@code onErrorResume(RedisException.class, ...)}
 *       path when {@code trySetPermits}/{@code tryAcquire} throw;</li>
 *   <li>{@code LockInstance.release} tolerating a {@code tryRelease} failure without
 *       breaking the held-chain (release is fire-and-forget).</li>
 * </ul>
 * All other behavior — happy paths, action terminal release, concurrent contention,
 * lease eviction, custom expire — is covered by
 * {@link RedissonLockServiceIntegrationTest} against real Redis. This class exists only
 * for fault-injection scenarios that the integration test cannot deterministically reach.
 */
@ExtendWith(MockitoExtension.class)
class RedissonLockServiceTest {

    private static final int LOCK_TIMEOUT_MS = 1_000;
    private static final int TTL_IN_SECONDS = 60;
    private static final Duration ACTION_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration LOCK_WAIT_TIME = Duration.ofMillis(100);
    private static final long ACTION_TIMEOUT_MS = ACTION_TIMEOUT.toMillis();
    private static final long LOCK_WAIT_TIME_MS = LOCK_WAIT_TIME.toMillis();
    private static final int RANDOM_STRING_LENGTH = 32;

    @Mock
    private RedissonReactiveClient redisClient;

    @Mock
    private RPermitExpirableSemaphoreReactive semaphore;

    private RedissonLockService lockService;

    @BeforeEach
    void setUp() {
        var config = DistributedLockConfig.builder()
                .lockTimeoutMS(LOCK_TIMEOUT_MS)
                .ttlInSeconds(TTL_IN_SECONDS)
                .build();
        when(redisClient.getPermitExpirableSemaphore(any(CommonOptions.class))).thenReturn(semaphore);
        lockService = new RedissonLockService(redisClient, config);
    }

    @Test
    void executeWithLockRetriesOnRedisExceptionUpToMax() {
        // trySetPermits errors every time; retry should kick in for the configured max.
        when(semaphore.trySetPermits(1))
                .thenReturn(Mono.error(new RedisException("connection lost")));

        var lock = new LockService.Lock(UUID.randomUUID(),
                "lockName-%s".formatted(RandomStringUtils.secure().nextAlphanumeric(RANDOM_STRING_LENGTH)));

        // Reactor wraps the original failure in a RetryExhaustedException once retries are
        // spent; the underlying cause should be the RedisException we kept throwing.
        StepVerifier.create(lockService.executeWithLock(lock, Mono.just("never-runs")))
                .expectErrorMatches(throwable -> Exceptions.isRetryExhausted(throwable)
                        && throwable.getCause() instanceof RedisException)
                .verify();

        // Retry.max(3) means 1 initial attempt + 3 retries = 4 invocations of the upstream.
        verify(semaphore, times(4)).trySetPermits(1);
    }

    @Test
    void acquireLockDoesNotRetryNonRedisException() {
        // The retryWhen filter only matches RedisException; any other failure must
        // propagate immediately without consuming the 3 retry attempts.
        when(semaphore.trySetPermits(1))
                .thenReturn(Mono.error(new IllegalStateException("not a RedisException")));

        var lock = new LockService.Lock(UUID.randomUUID(),
                "lockName-%s".formatted(RandomStringUtils.secure().nextAlphanumeric(RANDOM_STRING_LENGTH)));

        StepVerifier.create(lockService.executeWithLock(lock, Mono.just("never-runs")))
                .expectError(IllegalStateException.class)
                .verify();

        verify(semaphore, times(1)).trySetPermits(1);
    }

    @Test
    void bestEffortLockReleasesPermitWhenExpireErrors() {
        var permitId = "permitId-%s".formatted(RandomStringUtils.secure().nextAlphanumeric(RANDOM_STRING_LENGTH));
        when(semaphore.trySetPermits(1)).thenReturn(Mono.just(true));
        when(semaphore.tryAcquire(LOCK_WAIT_TIME_MS, ACTION_TIMEOUT_MS, TimeUnit.MILLISECONDS))
                .thenReturn(Mono.just(permitId));
        when(semaphore.expire(ACTION_TIMEOUT))
                .thenReturn(Mono.error(new RedisException("expire failed")));
        when(semaphore.tryRelease(permitId)).thenReturn(Mono.just(true));

        var lock = new LockService.Lock(UUID.randomUUID(),
                "lockName-%s".formatted(RandomStringUtils.secure().nextAlphanumeric(RANDOM_STRING_LENGTH)));

        // RedisException from expire is caught by the outer onErrorResume; the chain
        // completes empty after running the (empty) fallback.
        StepVerifier.create(lockService.bestEffortLock(
                lock,
                Mono.just("should-not-run"),
                Mono.empty(),
                ACTION_TIMEOUT,
                LOCK_WAIT_TIME))
                .verifyComplete();

        // Inner doFinally in expire() must release the just-acquired permit. The outer
        // doFinally also fires ON_ERROR and may release again (harmless idempotent no-op),
        // hence atLeastOnce. Mockito's timeout polls until the verification holds, absorbing
        // the small window where the release fires on the scheduler thread.
        verify(semaphore, timeout(ACTION_TIMEOUT_MS).atLeastOnce()).tryRelease(permitId);
    }

    @Test
    void bestEffortLockReleasesPermitWhenExpireCancelled() {
        var permitId = "permitId-%s".formatted(RandomStringUtils.secure().nextAlphanumeric(RANDOM_STRING_LENGTH));
        when(semaphore.trySetPermits(1)).thenReturn(Mono.just(true));
        when(semaphore.tryAcquire(LOCK_WAIT_TIME_MS, ACTION_TIMEOUT_MS, TimeUnit.MILLISECONDS))
                .thenReturn(Mono.just(permitId));
        // expire never completes — we'll dispose the subscription mid-flight.
        when(semaphore.expire(ACTION_TIMEOUT)).thenReturn(Mono.never());
        when(semaphore.tryRelease(permitId)).thenReturn(Mono.just(true));

        var lock = new LockService.Lock(UUID.randomUUID(),
                "lockName-%s".formatted(RandomStringUtils.secure().nextAlphanumeric(RANDOM_STRING_LENGTH)));

        var disposable = lockService.bestEffortLock(
                lock,
                Mono.just("should-not-run"),
                Mono.empty(),
                ACTION_TIMEOUT,
                LOCK_WAIT_TIME)
                .subscribe();

        // Give the chain time to reach the expire(...) subscription.
        TestUtils.waitForMillis(LOCK_WAIT_TIME_MS);
        disposable.dispose();

        verify(semaphore, timeout(ACTION_TIMEOUT_MS).atLeastOnce()).tryRelease(permitId);
    }

    @Test
    void bestEffortLockRunsFallbackOnRedisException() {
        // RedisException from trySetPermits must route to the outer onErrorResume,
        // which runs the fallback and the chain completes empty (no retry — that's
        // executeWithLock's contract, not bestEffortLock's).
        when(semaphore.trySetPermits(1))
                .thenReturn(Mono.error(new RedisException("trySetPermits failed")));

        var fallbackRan = new AtomicBoolean(false);
        Mono<Void> fallback = Mono.fromRunnable(() -> fallbackRan.set(true));

        var lock = new LockService.Lock(UUID.randomUUID(),
                "lockName-%s".formatted(RandomStringUtils.secure().nextAlphanumeric(RANDOM_STRING_LENGTH)));

        StepVerifier.create(lockService.bestEffortLock(
                lock,
                Mono.just("should-not-run"),
                fallback,
                ACTION_TIMEOUT,
                LOCK_WAIT_TIME))
                .verifyComplete();

        // Single attempt — bestEffortLock has no retryWhen, unlike executeWithLock.
        verify(semaphore, times(1)).trySetPermits(1);
        assertThat(fallbackRan)
                .as("fallback must run when trySetPermits errors")
                .isTrue();
    }

    static Stream<Arguments> bestEffortLockInstanceReleaseHandlesTryReleaseOutcome() {
        return Stream.of(
                // Script ran but found nothing to release (lease already expired by score, or
                // a concurrent path cleaned up first) → INFO no-op log.
                Arguments.of("no-op", Mono.just(false)),
                // tryRelease itself errors → release's internal subscribe catches it and logs
                // WARN. The chain must still emit the action result either way.
                Arguments.of("error", Mono.error(new RedisException("tryRelease failed"))));
    }

    @ParameterizedTest(name = "tryRelease {0} leaves the chain unaffected")
    @MethodSource
    void bestEffortLockInstanceReleaseHandlesTryReleaseOutcome(
            String label, Mono<Boolean> tryReleaseOutcome) {
        var permitId = "permitId-%s".formatted(RandomStringUtils.secure().nextAlphanumeric(RANDOM_STRING_LENGTH));
        when(semaphore.trySetPermits(1)).thenReturn(Mono.just(true));
        when(semaphore.tryAcquire(LOCK_WAIT_TIME_MS, ACTION_TIMEOUT_MS, TimeUnit.MILLISECONDS))
                .thenReturn(Mono.just(permitId));
        when(semaphore.expire(ACTION_TIMEOUT)).thenReturn(Mono.just(true));
        when(semaphore.tryRelease(permitId)).thenReturn(tryReleaseOutcome);
        var expectedValue = "action-result-%s".formatted(label);

        var lock = new LockService.Lock(UUID.randomUUID(),
                "lockName-%s".formatted(RandomStringUtils.secure().nextAlphanumeric(RANDOM_STRING_LENGTH)));

        StepVerifier.create(lockService.bestEffortLock(
                lock,
                Mono.just(expectedValue),
                Mono.empty(),
                ACTION_TIMEOUT,
                LOCK_WAIT_TIME))
                .expectNext(expectedValue)
                .verifyComplete();

        verify(semaphore, timeout(ACTION_TIMEOUT_MS).atLeastOnce()).tryRelease(permitId);
    }
}
