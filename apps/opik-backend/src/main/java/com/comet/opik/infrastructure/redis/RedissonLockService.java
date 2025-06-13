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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@RequiredArgsConstructor
@Slf4j
class RedissonLockService implements LockService {

    private static final String LOCK_ACQUIRED = "Lock '{}' acquired";
    private static final String TRYING_TO_LOCK_WITH = "Trying to lock with '{}'";
    private static final Consumer<Void> NO_OP = __ -> {
    };

    private final @NonNull RedissonReactiveClient redisClient;
    private final @NonNull DistributedLockConfig distributedLockConfig;

    private record LockInstance(RPermitExpirableSemaphoreReactive semaphore, String locked) {

        public void release(Lock lock) {
            semaphore.release(locked)
                    .subscribe(NO_OP,
                            __ -> log.warn("Lock already released or doesn't exist"),
                            () -> log.debug("Lock {} released successfully", lock));
        }

    }

    @Override
    public <T> Mono<T> executeWithLock(@NonNull Lock lock, @NonNull Mono<T> action) {

        RPermitExpirableSemaphoreReactive semaphore = getSemaphore(lock);

        log.debug(TRYING_TO_LOCK_WITH, lock);

        return acquireLock(semaphore, Duration.ofMillis(distributedLockConfig.getLockTimeoutMS()))
                .flatMap(lockInstance -> runAction(lock, action, lockInstance.locked())
                        .subscribeOn(Schedulers.boundedElastic())
                        .doFinally(signalType -> lockInstance.release(lock)));
    }

    @Override
    public <T> Mono<T> executeWithLockCustomExpire(@NonNull Lock lock, @NonNull Mono<T> action, Duration duration) {

        RPermitExpirableSemaphoreReactive semaphore = getSemaphore(lock);

        log.debug(TRYING_TO_LOCK_WITH, lock);

        return acquireLock(semaphore, duration)
                .flatMap(lockInstance -> runAction(lock, action, lockInstance.locked())
                        .subscribeOn(Schedulers.boundedElastic())
                        .doFinally(signalType -> lockInstance.release(lock)));
    }

    private RPermitExpirableSemaphoreReactive getSemaphore(Lock lock) {
        return redisClient.getPermitExpirableSemaphore(
                CommonOptions
                        .name(lock.key())
                        .timeout(Duration.ofMillis(distributedLockConfig.getLockTimeoutMS()))
                        .retryInterval(Duration.ofMillis(10))
                        .retryAttempts(distributedLockConfig.getLockTimeoutMS() / 10));
    }

    private Mono<LockInstance> acquireLock(RPermitExpirableSemaphoreReactive semaphore, Duration duration) {
        return Mono.defer(() -> acquire(semaphore, duration))
                .retryWhen(Retry.max(3).filter(RedisException.class::isInstance));
    }

    private Mono<LockInstance> acquire(RPermitExpirableSemaphoreReactive semaphore, Duration duration) {
        Duration defaultLockTTL = Duration.ofSeconds(distributedLockConfig.getTtlInSeconds());

        // Ensure the TTL is at least as long as the lock duration
        long ttlInMillis = Math.max(duration.toMillis(), defaultLockTTL.toMillis());

        return semaphore
                .setPermits(1)
                .then(Mono.defer(() -> semaphore.acquire(duration.toMillis(), TimeUnit.MILLISECONDS)))
                .flatMap(locked -> expire(Duration.ofMillis(ttlInMillis), locked, semaphore));
    }

    private <T> Mono<T> runAction(Lock lock, Mono<T> action, String locked) {
        if (locked != null) {
            log.debug(LOCK_ACQUIRED, lock);
            return action;
        }

        return Mono.error(new IllegalStateException("Could not acquire lock"));
    }

    @Override
    public <T> Flux<T> executeWithLock(@NonNull Lock lock, @NonNull Flux<T> stream) {

        RPermitExpirableSemaphoreReactive semaphore = getSemaphore(lock);

        log.debug(TRYING_TO_LOCK_WITH, lock);

        return acquireLock(semaphore, Duration.ofMillis(distributedLockConfig.getLockTimeoutMS()))
                .flatMapMany(lockInstance -> stream(lock, stream, lockInstance.locked())
                        .subscribeOn(Schedulers.boundedElastic())
                        .doFinally(signalType -> lockInstance.release(lock)));
    }

    @Override
    public <T> Mono<T> bestEffortLock(Lock lock, Mono<T> action, Mono<T> failToAcquireLockAction,
            Duration actionTimeout, Duration lockWaitTime) {
        RPermitExpirableSemaphoreReactive semaphore = getSemaphore(lock);
        log.debug(TRYING_TO_LOCK_WITH, lock);

        return Mono.defer(() -> semaphore.setPermits(1)
                //Try to acquire the lock until the lockWaitTime expires if the lock is not available it will return Mono.empty()
                // If the lock is acquired, it sets the expiration time using the actionTimeout
                .then(Mono.defer(() -> semaphore.tryAcquire(lockWaitTime.toMillis(), actionTimeout.toMillis(),
                        TimeUnit.MILLISECONDS)))
                .flatMap(locked -> expire(actionTimeout, locked, semaphore)))
                .flatMap(lockInstance -> runAction(lock, action, lockInstance))
                .onErrorResume(RedisException.class, e -> handleError(lock, failToAcquireLockAction, e))
                .onErrorResume(IllegalStateException.class, e -> handleError(lock, failToAcquireLockAction, e))
                .switchIfEmpty(failToAcquireLockAction);
    }

    private <T> Mono<T> runAction(Lock lock, Mono<T> action, LockInstance lockInstance) {
        return runAction(lock, action, lockInstance.locked())
                .subscribeOn(Schedulers.boundedElastic())
                .doFinally(signalType -> lockInstance.release(lock));
    }

    private Mono<LockInstance> expire(Duration actionTimeout, String locked,
            RPermitExpirableSemaphoreReactive semaphore) {
        return semaphore.expire(actionTimeout)
                .thenReturn(new LockInstance(semaphore, locked));
    }

    private static <T> Mono<T> handleError(Lock lock, Mono<T> failToAcquireLockAction, Exception e) {
        log.warn("Failed to acquire lock '{}', executing fallback action", lock, e);
        return failToAcquireLockAction;
    }

    private <T> Flux<T> stream(Lock lock, Flux<T> action, String locked) {
        if (locked != null) {
            log.debug(LOCK_ACQUIRED, lock);
            return action;
        }

        return Flux.error(new IllegalStateException("Could not acquire lock"));
    }
}
