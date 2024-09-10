package com.comet.opik.infrastructure.redis;

import com.comet.opik.infrastructure.DistributedLockConfig;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RequiredArgsConstructor
@Slf4j
class RedissonLockService implements LockService {

    private final @NonNull RedissonReactiveClient redisClient;
    private final @NonNull DistributedLockConfig distributedLockConfig;

    @Override
    public Mono<List<LockRef>> lockAll(@NonNull List<UUID> keys, @NonNull String suffix) {
        if (keys.isEmpty()) {
            return Mono.empty();
        }

        return Flux.fromIterable(keys)
                .map(key -> new Lock(key, suffix))
                .flatMap(lock -> {
                    RPermitExpirableSemaphoreReactive semaphore = getSemaphore(lock, distributedLockConfig.getBulkLockTimeoutMS());
                    log.debug("Trying to lock with {}", lock);
                    return semaphore.trySetPermits(1).thenReturn(Map.entry(lock, semaphore));
                })
                .flatMap(entry -> entry.getValue().acquire()
                        .flatMap(locked -> Mono.just(new LockRef(entry.getKey(), locked))))
                .collectList();
    }

    @Override
    public Mono<Void> unlockAll(@NonNull List<LockRef> locks) {
        if (locks.isEmpty()) {
            return Mono.empty();
        }

        return Flux.fromIterable(locks)
                .flatMap(lock -> {
                    RPermitExpirableSemaphoreReactive semaphore = getSemaphore(lock.lock(), distributedLockConfig.getBulkLockTimeoutMS());
                    log.debug("Trying to unlock with {}", lock);
                    return semaphore.release(lock.ref());
                })
                .collectList()
                .then();
    }

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
