package com.comet.opik.infrastructure.redis;

import com.comet.opik.infrastructure.DistributedLockConfig;
import com.comet.opik.infrastructure.lock.LockService;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RPermitExpirableSemaphoreReactive;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.api.options.CommonOptions;
import org.redisson.client.RedisException;
import org.redisson.config.ConstantDelay;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@RequiredArgsConstructor
@Slf4j
class RedissonLockService implements LockService {

    private static final String LOCK_ACQUIRED = "Lock acquired '{}'";
    private static final String TRYING_TO_LOCK_WITH = "Trying to lock with '{}'";

    private final @NonNull RedissonReactiveClient redisClient;
    private final @NonNull DistributedLockConfig distributedLockConfig;
    private final LockMetrics lockMetrics = new LockMetrics();

    private record LockInstance(RPermitExpirableSemaphoreReactive semaphore, String permitId) {

        /**
         * Release the permit using {@code tryRelease}, which lets us distinguish the three
         * outcomes that production observability needs:
         * <ul>
         *   <li>{@code true} — the permit was released cleanly (the expected happy path);</li>
         *   <li>{@code false} — the script ran but found nothing to release. This is either
         *       a permit that another path already cleaned up (the underlying key TTL fired,
         *       a concurrent {@code tryAcquire} expired-permits cleanup, …) or one whose
         *       lease score had already passed (the action ran past the configured lock
         *       timeout).</li>
         *   <li>error — the script could not run at all (network, connection error, etc.).</li>
         * </ul>
         */
        private void release() {
            semaphore.tryRelease(permitId)
                    .subscribe(released -> {
                        if (Boolean.TRUE.equals(released)) {
                            log.debug("Lock released");
                        } else {
                            log.info("Lock release was a no-op: permit already gone or lease expired");
                        }
                    }, throwable -> log.warn("Failed to release lock", throwable));
        }
    }

    @Override
    public <T> Mono<T> executeWithLock(@NonNull Lock lock, @NonNull Mono<T> action) {
        return executeWithLockCustomExpire(lock, action, Duration.ofMillis(distributedLockConfig.getLockTimeoutMS()));
    }

    @Override
    public <T> Mono<T> executeWithLockCustomExpire(
            @NonNull Lock lock, @NonNull Mono<T> action, @NonNull Duration duration) {
        var semaphore = getSemaphore(lock);
        var metricLock = LockMetrics.label(lock.key());
        log.debug(TRYING_TO_LOCK_WITH, lock);
        return instrumentedAcquire(metricLock, acquireLock(semaphore, duration))
                .flatMap(lockInstance -> runAction(lock, action, lockInstance)
                        .doFinally(signalType -> lockMetrics.heldEnd(metricLock)));
    }

    @Override
    public <T> Flux<T> executeWithLock(@NonNull Lock lock, @NonNull Flux<T> stream) {
        var semaphore = getSemaphore(lock);
        var metricLock = LockMetrics.label(lock.key());
        log.debug(TRYING_TO_LOCK_WITH, lock);
        return instrumentedAcquire(metricLock,
                acquireLock(semaphore, Duration.ofMillis(distributedLockConfig.getLockTimeoutMS())))
                .flatMapMany(lockInstance -> runStream(lock, stream, lockInstance)
                        .doFinally(signalType -> lockMetrics.heldEnd(metricLock)));
    }

    /**
     * Wrap a permit-acquire with waiting/held gauges + acquire-time/outcome metrics. {@code lock_waiting}
     * is incremented on subscribe and decremented on any terminal signal (success, error, cancel), so a
     * waiter that times out, errors, or is cancelled never leaks the gauge. {@code lock_held} is bumped
     * once the permit is obtained; the caller decrements it when the held action terminates.
     */
    private Mono<LockInstance> instrumentedAcquire(String metricLock, Mono<LockInstance> acquire) {
        return Mono.defer(() -> {
            long start = System.currentTimeMillis();
            lockMetrics.waitStart(metricLock);
            return acquire
                    .doOnNext(lockInstance -> {
                        lockMetrics.acquired(metricLock, System.currentTimeMillis() - start);
                        lockMetrics.heldStart(metricLock);
                    })
                    .doOnError(throwable -> lockMetrics.acquireFailed(metricLock))
                    .doFinally(signalType -> lockMetrics.waitEnd(metricLock));
        });
    }

    private RPermitExpirableSemaphoreReactive getSemaphore(Lock lock) {
        return redisClient.getPermitExpirableSemaphore(
                CommonOptions
                        .name(lock.key())
                        .timeout(Duration.ofMillis(distributedLockConfig.getLockTimeoutMS()))
                        .retryDelay(new ConstantDelay(Duration.ofMillis(10)))
                        .retryAttempts(distributedLockConfig.getLockTimeoutMS() / 10));
    }

    private Mono<LockInstance> acquireLock(RPermitExpirableSemaphoreReactive semaphore, Duration duration) {
        return Mono.defer(() -> acquire(semaphore, duration))
                .retryWhen(Retry.max(3).filter(RedisException.class::isInstance));
    }

    private Mono<LockInstance> acquire(RPermitExpirableSemaphoreReactive semaphore, Duration duration) {
        var defaultLockTTL = Duration.ofSeconds(distributedLockConfig.getTtlInSeconds());
        // Ensure the TTL is at least as long as the lock duration
        long ttlInMillis = Math.max(duration.toMillis(), defaultLockTTL.toMillis());
        return semaphore
                .trySetPermits(1)
                .then(Mono.defer(() -> semaphore.acquire(duration.toMillis(), TimeUnit.MILLISECONDS)))
                .flatMap(permitId -> {
                    var lockInstance = new LockInstance(semaphore, permitId);
                    return expire(lockInstance, Duration.ofMillis(ttlInMillis))
                            .thenReturn(lockInstance);
                });
    }

    /** Wrap a held-action and release the permit on any terminal signal. */
    private <T> Mono<T> runAction(Lock lock, Mono<T> action, LockInstance lockInstance) {
        return runAction(lock, action)
                .doFinally(signalType -> lockInstance.release());
    }

    /**
     * Log {@code LOCK_ACQUIRED} and subscribe on {@code Schedulers.boundedElastic()} so we
     * don't block Redisson's event loop with caller work.
     */
    private <T> Mono<T> runAction(Lock lock, Mono<T> action) {
        return action
                .doOnSubscribe(subscription -> log.debug(LOCK_ACQUIRED, lock))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** Flux variant of {@link #runAction}. */
    private <T> Flux<T> runStream(Lock lock, Flux<T> stream, LockInstance lockInstance) {
        return stream
                .doOnSubscribe(subscription -> log.debug(LOCK_ACQUIRED, lock))
                .subscribeOn(Schedulers.boundedElastic())
                .doFinally(signalType -> lockInstance.release());
    }

    /**
     * Attempts to acquire a lock and execute an action with a fallback if the lock cannot be acquired.
     *
     * @param lock The lock to be acquired.
     * @param action The action to be executed.
     * @param failToAcquireLockAction The action to be executed if the lock cannot be acquired.
     * @param actionTimeout Lease time for the held permit and TTL applied to the underlying lock keys.
     *                      The action itself is <b>not</b> subject to this timeout; if the caller wants
     *                      the action canceled when the lease elapses, they should wrap it with their
     *                      own {@code .timeout(...)} before passing it in.
     * @param lockWaitTime The maximum time to wait for the lock to be acquired.
     *
     * @return Mono.empty if the lock could not be acquired, otherwise it returns the result of the action.
     * **/
    @Override
    public <T> Mono<T> bestEffortLock(Lock lock, Mono<T> action, Mono<Void> failToAcquireLockAction,
            Duration actionTimeout, Duration lockWaitTime) {
        return bestEffortLock(lock, action, failToAcquireLockAction, actionTimeout, lockWaitTime, false);
    }

    @Override
    public <T> Mono<T> bestEffortLock(Lock lock, Mono<T> action, Mono<Void> failToAcquireLockAction,
            Duration actionTimeout, Duration lockWaitTime, boolean holdUntilExpiry) {
        var semaphore = getSemaphore(lock);
        var metricLock = LockMetrics.label(lock.key());
        log.debug(TRYING_TO_LOCK_WITH, lock);
        return Mono.defer(() -> {
            long start = System.currentTimeMillis();
            lockMetrics.waitStart(metricLock);
            return semaphore.trySetPermits(1)
                    //Try to acquire the lock until the lockWaitTime expires if the lock is not available it will return Mono.empty()
                    .then(Mono.defer(() -> semaphore.tryAcquire(
                            lockWaitTime.toMillis(), actionTimeout.toMillis(), TimeUnit.MILLISECONDS))
                            // An empty completion means the wait elapsed without obtaining a permit.
                            .doOnSuccess(permitId -> {
                                if (permitId == null) {
                                    lockMetrics.acquireFailed(metricLock);
                                }
                            })
                            // If the lock is not acquired, run the fallback and return empty so the main action does not execute.
                            .switchIfEmpty(failToAcquireLockAction.then(Mono.empty()))
                            .flatMap(permitId -> {
                                lockMetrics.acquired(metricLock, System.currentTimeMillis() - start);
                                lockMetrics.heldStart(metricLock);
                                var lockInstance = new LockInstance(semaphore, permitId);
                                // If the lock is acquired, it sets the expiration time using the actionTimeout
                                return expire(lockInstance, actionTimeout)
                                        .then(Mono.defer(() -> runAction(lock, action)))
                                        .doFinally(signal -> {
                                            lockMetrics.heldEnd(metricLock);
                                            if (!holdUntilExpiry) {
                                                lockInstance.release();
                                            }
                                        });
                            }))
                    .doFinally(signal -> lockMetrics.waitEnd(metricLock));
        })
                .onErrorResume(RedisException.class,
                        redisException -> {
                            lockMetrics.acquireFailed(metricLock);
                            return handleError(failToAcquireLockAction, redisException).then(Mono.empty());
                        });
    }

    @Override
    public Mono<Boolean> lockUsingToken(@NonNull Lock lock, @NonNull Duration lockDuration) {
        return redisClient.getBucket(lock.key()).setIfAbsent("ok", lockDuration);
    }

    @Override
    public Mono<Void> unlockUsingToken(@NonNull Lock lock) {
        return redisClient.getBucket(lock.key()).delete().then();
    }

    /**
     * Extend the TTL on a permit that was just acquired. If {@code semaphore.expire(...)}
     * does not complete normally, release the permit before propagating the signal.
     * Otherwise, the failed attempt would orphan an entry in the timeout zset (and in
     * {@code executeWithLock}'s retry path, every retry that hits this would leak a fresh
     * permit on top of the previous one).
     */
    private Mono<Boolean> expire(LockInstance lockInstance, Duration ttl) {
        return lockInstance.semaphore()
                .expire(ttl)
                .doFinally(signal -> {
                    if (signal != SignalType.ON_COMPLETE) {
                        lockInstance.release();
                    }
                });
    }

    private <T> Mono<T> handleError(Mono<T> failToAcquireLockAction, Exception exception) {
        log.warn("Failed to acquire lock, executing fallback action", exception);
        return failToAcquireLockAction;
    }

    // --- Slot-based capacity locking ---

    @Override
    public Mono<String> tryAcquireSlot(@NonNull Lock lock, int totalSlots, @NonNull Duration leaseTime) {
        var semaphore = slotSemaphore(lock);
        return semaphore.trySetPermits(totalSlots)
                .then(Mono.defer(() -> semaphore.tryAcquire(0L, leaseTime.toMillis(), TimeUnit.MILLISECONDS)))
                .flatMap(permitId -> semaphore.expire(leaseTime).thenReturn(permitId));
    }

    @Override
    public Mono<Boolean> refreshSlot(@NonNull Lock lock, @NonNull String permitId, @NonNull Duration leaseTime) {
        var semaphore = slotSemaphore(lock);
        return semaphore.updateLeaseTime(permitId, leaseTime.toMillis(), TimeUnit.MILLISECONDS)
                .flatMap(result -> Boolean.TRUE.equals(result)
                        ? semaphore.expire(leaseTime).thenReturn(true)
                        : Mono.just(false));
    }

    @Override
    public Mono<Boolean> releaseSlot(@NonNull Lock lock, @NonNull String permitId) {
        return slotSemaphore(lock).tryRelease(permitId);
    }

    @Override
    public Mono<Void> addSlotPermits(@NonNull Lock lock, int delta) {
        return slotSemaphore(lock).addPermits(delta);
    }

    private RPermitExpirableSemaphoreReactive slotSemaphore(Lock lock) {
        return redisClient.getPermitExpirableSemaphore(CommonOptions.name(lock.key()));
    }
}
