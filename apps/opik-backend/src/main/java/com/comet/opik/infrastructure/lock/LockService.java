package com.comet.opik.infrastructure.lock;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
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
    <T> Mono<T> executeWithLockCustomExpire(Lock lock, Mono<T> action, Duration duration);
    <T> Flux<T> executeWithLock(Lock lock, Flux<T> action);
    <T> Mono<T> bestEffortLock(Lock lock, Mono<T> action, Mono<Void> failToAcquireLockAction, Duration actionTimeout,
            Duration lockTimeout);

    Mono<Boolean> lockUsingToken(Lock lock, Duration lockDuration);
    Mono<Void> unlockUsingToken(Lock lock);
}
