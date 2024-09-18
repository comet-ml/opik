package com.comet.opik.infrastructure.redis;

import com.comet.opik.infrastructure.DistributedLockConfig;
import com.comet.opik.infrastructure.lock.LockService;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RPermitExpirableSemaphoreReactive;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.api.options.CommonOptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;

@RequiredArgsConstructor
@Slf4j
class RedissonLockService implements LockService {

    private final @NonNull RedissonReactiveClient redisClient;
    private final @NonNull DistributedLockConfig distributedLockConfig;

    @Override
    public <T> Mono<T> executeWithLock(@NonNull Lock lock, @NonNull Mono<T> action) {

        RPermitExpirableSemaphoreReactive semaphore = getSemaphore(lock, distributedLockConfig.getLockTimeoutMS());

        log.debug("Trying to lock with {}", lock);

        return semaphore
                .trySetPermits(1)
                .then(Mono.defer(semaphore::acquire))
                .flatMap(locked -> runAction(lock, action, locked)
                        .subscribeOn(Schedulers.boundedElastic())
                        .doFinally(signalType -> {
                            semaphore.release(locked).subscribe();
                            log.debug("Lock {} released", lock);
                        }));
    }

    private RPermitExpirableSemaphoreReactive getSemaphore(Lock lock, int lockTimeoutMS) {
        return redisClient.getPermitExpirableSemaphore(
                CommonOptions
                        .name(lock.key())
                        .timeout(Duration.ofMillis(lockTimeoutMS))
                        .retryInterval(Duration.ofMillis(10))
                        .retryAttempts(lockTimeoutMS / 10));
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
        RPermitExpirableSemaphoreReactive semaphore = getSemaphore(lock, distributedLockConfig.getLockTimeoutMS());

        return semaphore
                .trySetPermits(1)
                .then(Mono.defer(semaphore::acquire))
                .flatMapMany(locked -> stream(lock, stream, locked)
                        .subscribeOn(Schedulers.boundedElastic())
                        .doFinally(signalType -> {
                            semaphore.release(locked).subscribe();
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
