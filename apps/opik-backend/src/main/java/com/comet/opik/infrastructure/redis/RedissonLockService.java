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

@RequiredArgsConstructor
@Slf4j
class RedissonLockService implements LockService {

    private final @NonNull RedissonReactiveClient redisClient;
    private final @NonNull DistributedLockConfig distributedLockConfig;

    private record LockInstance(RPermitExpirableSemaphoreReactive semaphore, String locked) {

        public void release() {
            semaphore.release(locked)
                    .subscribe(
                            __ -> log.debug("Lock {} released successfully", locked),
                            __ -> log.warn("Lock {} already released", locked));
        }

    }

    @Override
    public <T> Mono<T> executeWithLock(@NonNull Lock lock, @NonNull Mono<T> action) {

        RPermitExpirableSemaphoreReactive semaphore = getSemaphore(lock);

        log.debug("Trying to lock with {}", lock);

        return acquireLock(semaphore)
                .flatMap(lockInstance -> runAction(lock, action, lockInstance.locked())
                        .subscribeOn(Schedulers.boundedElastic())
                        .doFinally(signalType -> {
                            lockInstance.release();
                            log.debug("Lock {} released", lock);
                        }));
    }

    private RPermitExpirableSemaphoreReactive getSemaphore(Lock lock) {
        return redisClient.getPermitExpirableSemaphore(
                CommonOptions
                        .name(lock.key())
                        .timeout(Duration.ofMillis(distributedLockConfig.getLockTimeoutMS()))
                        .retryInterval(Duration.ofMillis(10))
                        .retryAttempts(distributedLockConfig.getLockTimeoutMS() / 10));
    }

    private Mono<LockInstance> acquireLock(RPermitExpirableSemaphoreReactive semaphore) {
        return Mono.defer(() -> acquire(semaphore))
                .retryWhen(Retry.max(3).filter(RedisException.class::isInstance));
    }

    private Mono<LockInstance> acquire(RPermitExpirableSemaphoreReactive semaphore) {
        return semaphore
                .setPermits(1)
                .then(Mono.defer(
                        () -> semaphore.acquire(distributedLockConfig.getLockTimeoutMS(), TimeUnit.MILLISECONDS)))
                .flatMap(locked -> semaphore.expire(Duration.ofSeconds(distributedLockConfig.getTtlInSeconds()))
                        .thenReturn(new LockInstance(semaphore, locked)));
    }

    private <T> Mono<T> runAction(Lock lock, Mono<T> action, String locked) {
        if (locked != null) {
            log.debug("Lock {} acquired", lock);
            return action;
        }

        return Mono.error(new IllegalStateException("Could not acquire lock"));
    }

    @Override
    public <T> Flux<T> executeWithLock(@NonNull Lock lock, @NonNull Flux<T> stream) {

        RPermitExpirableSemaphoreReactive semaphore = getSemaphore(lock);

        log.debug("Trying to lock with {}", lock);

        return acquireLock(semaphore)
                .flatMapMany(lockInstance -> stream(lock, stream, lockInstance.locked())
                        .subscribeOn(Schedulers.boundedElastic())
                        .doFinally(signalType -> {
                            lockInstance.release();
                            log.debug("Lock {} released", lock);
                        }));
    }

    private <T> Flux<T> stream(Lock lock, Flux<T> action, String locked) {
        if (locked != null) {
            log.debug("Lock {} acquired", lock);
            return action;
        }

        return Flux.error(new IllegalStateException("Could not acquire lock"));
    }
}
