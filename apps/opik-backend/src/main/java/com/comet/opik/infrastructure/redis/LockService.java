package com.comet.opik.infrastructure.redis;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

public interface LockService {

    Mono<List<LockRef>> lockAll(List<UUID> spanIds, String spanKey);
    Mono<Void> unlockAll(List<LockRef> locks);

    record Lock(String key) {

        private static final String KEY_FORMAT = "%s-%s";

        public Lock(UUID id, String name) {
            this(KEY_FORMAT.formatted(id, name));
        }

        public Lock(String id, String name) {
            this(KEY_FORMAT.formatted(id, name));
        }

    }

    record LockRef(Lock lock, String ref) {
    }

    <T> Mono<T> executeWithLock(Lock lock, Mono<T> action);
    <T> Flux<T> executeWithLock(Lock lock, Flux<T> action);
}
