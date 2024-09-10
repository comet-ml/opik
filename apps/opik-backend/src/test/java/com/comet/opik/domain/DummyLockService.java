package com.comet.opik.domain;

import com.comet.opik.infrastructure.redis.LockService;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

public class DummyLockService implements LockService {

    @Override
    public Mono<List<LockRef>> lockAll(List<UUID> spanIds, String spanKey) {
        return Mono.empty();
    }

    @Override
    public Mono<Void> unlockAll(List<LockRef> locks) {
        return Mono.empty();
    }

    @Override
    public <T> Mono<T> executeWithLock(LockService.Lock lock, Mono<T> action) {
        return action;
    }

    @Override
    public <T> Flux<T> executeWithLock(LockService.Lock lock, Flux<T> action) {
        return action;
    }
}