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
    public <T> Mono<T> bestEffortLock(Lock lock, Mono<T> action, Mono<Void> failToAcquireLockAction,
            Duration actionTimeout, Duration lockTimeout, boolean holdUntilExpiry) {
        return action;
    }

    @Override
    public <T> Mono<T> executeWithLockCustomExpire(LockService.Lock lock, Mono<T> action, Duration duration) {
        return action;
    }

    @Override
    public Mono<Boolean> lockUsingToken(Lock lock, Duration lockDuration) {
        return Mono.just(true);
    }

    @Override
    public Mono<Void> unlockUsingToken(Lock lock) {
        return Mono.just("ok").then();
    }

    @Override
    public Mono<String> tryAcquireSlot(Lock lock, int totalSlots, Duration leaseTime) {
        return Mono.just("dummy-permit");
    }

    @Override
    public Mono<Boolean> refreshSlot(Lock lock, String permitId, Duration leaseTime) {
        return Mono.just(true);
    }

    @Override
    public Mono<Boolean> releaseSlot(Lock lock, String permitId) {
        return Mono.just(true);
    }

    @Override
    public Mono<Void> addSlotPermits(Lock lock, int delta) {
        return Mono.empty();
    }
}
