package com.comet.opik.domain;

import com.comet.opik.infrastructure.lock.LockService;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;

public class DummyLockService implements LockService {

    @Override
    public <T> Mono<T> executeWithLock(LockService.Lock lock, Mono<T> action) {
        return action;
    }

    @Override
    public <T> Flux<T> executeWithLock(LockService.Lock lock, Flux<T> action) {
        return action;
    }

    @Override
    public <T> Mono<T> bestEffortLock(Lock lock, Mono<T> action, Mono<Void> failToAcquireLockAction,
            Duration actionTimeout, Duration lockTimeout) {
        return action;
    }

    @Override
    public <T> Mono<T> executeWithLockCustomExpire(LockService.Lock lock, Mono<T> action, Duration duration) {
        return action;
    }

    public Mono<Boolean> lockUsingToken(Lock lock, Duration lockDuration) {
        return Mono.just(true);
    }

    public Mono<Void> unlockUsingToken(Lock lock) {
        return Mono.just("ok").then();
    }

}
