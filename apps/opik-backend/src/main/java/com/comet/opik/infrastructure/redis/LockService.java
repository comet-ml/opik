package com.comet.opik.infrastructure.redis;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface LockService {

    record Lock(UUID id, String name) {
    }

    <T> Mono<T> executeWithLock(Lock lock, Mono<T> action);
    <T> Flux<T> executeWithLock(Lock lock, Flux<T> action);
}
