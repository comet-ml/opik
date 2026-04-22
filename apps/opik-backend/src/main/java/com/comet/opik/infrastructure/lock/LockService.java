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

    /**
     * Same as {@link #bestEffortLock(Lock, Mono, Mono, Duration, Duration)} but when {@code holdUntilExpiry} is true,
     * the lock is NOT released when the action completes — it expires naturally via TTL. Use this for periodic jobs
     * where you want to prevent other instances from running until the next cycle.
     */
    <T> Mono<T> bestEffortLock(Lock lock, Mono<T> action, Mono<Void> failToAcquireLockAction, Duration actionTimeout,
            Duration lockTimeout, boolean holdUntilExpiry);

    Mono<Boolean> lockUsingToken(Lock lock, Duration lockDuration);
    Mono<Void> unlockUsingToken(Lock lock);
}
