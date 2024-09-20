package com.comet.opik.infrastructure.lock;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface LockService {

    record Lock(String key) {

        private static final String KEY_FORMAT = "%s-%s";

        public Lock(UUID id, String name) {
            this(KEY_FORMAT.formatted(id, name));
        }

        public Lock(String id, String name) {
            this(KEY_FORMAT.formatted(id, name));
        }

    }

    <T> Mono<T> executeWithLock(Lock lock, Mono<T> action);
    <T> Flux<T> executeWithLock(Lock lock, Flux<T> action);
}
