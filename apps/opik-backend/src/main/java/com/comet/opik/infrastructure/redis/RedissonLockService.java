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
    private static final String LOCK_RELEASED = "Lock '{}' released";
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
        return semaphore
                .setPermits(1)
                .then(Mono.defer(
                        () -> semaphore.acquire(duration.toMillis(), TimeUnit.MILLISECONDS)))
                .flatMap(locked -> semaphore.expire(Duration.ofSeconds(distributedLockConfig.getTtlInSeconds()))
                        .thenReturn(new LockInstance(semaphore, locked)));
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

    private <T> Flux<T> stream(Lock lock, Flux<T> action, String locked) {
        if (locked != null) {
            log.debug(LOCK_ACQUIRED, lock);
            return action;
        }

        return Flux.error(new IllegalStateException("Could not acquire lock"));
    }
}
