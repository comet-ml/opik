package com.comet.opik.infrastructure.lock;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.UUID;
import java.util.regex.Pattern;

public interface LockService {

    record Lock(String key) {

        private static final String KEY_FORMAT = "%s-%s";

        // UUID ids in the key are the high-cardinality part (projectId, datasetId, …); collapse them so the
        // metric label is the stable lock type, e.g. "{projectId}-Trace" -> "*-Trace".
        private static final Pattern UUID_PATTERN = Pattern.compile(
                "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

        public Lock(UUID id, String name) {
            this(KEY_FORMAT.formatted(id, name));
        }

        public Lock(String id, String name) {
            this(KEY_FORMAT.formatted(id, name));
        }

        /**
         * Low-cardinality label for metrics: the key with embedded UUIDs collapsed to {@code *}. Bounds
         * lock-metric cardinality by lock type rather than by entity. Lives on the lock so observability
         * doesn't depend on callers — see {@code LockMetrics}.
         */
        public String metricName() {
            return UUID_PATTERN.matcher(key).replaceAll("*");
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

    /**
     * Try to atomically acquire a permit from a capacity-limited semaphore.
     * Returns the permitId if acquired, or empty if no permits available.
     */
    Mono<String> tryAcquireSlot(Lock lock, int totalSlots, Duration leaseTime);

    /**
     * Refresh the lease time of an existing permit.
     * Returns true if the permit was still valid and refreshed, false if expired.
     */
    Mono<Boolean> refreshSlot(Lock lock, String permitId, Duration leaseTime);

    /**
     * Release a permit back to the semaphore.
     * Returns true if released, false if already expired/released.
     */
    Mono<Boolean> releaseSlot(Lock lock, String permitId);

    /**
     * Adjust the total number of permits on an existing semaphore by a delta.
     * Positive delta increases capacity, negative reduces it.
     */
    Mono<Void> addSlotPermits(Lock lock, int delta);
}
