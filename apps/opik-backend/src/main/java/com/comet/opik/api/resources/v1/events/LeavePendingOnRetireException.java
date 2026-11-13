package com.comet.opik.api.resources.v1.events;

/**
 * Signals that {@code onRetired} could not finish (typically a failed re-queue XADD).
 * {@link BaseRedisSubscriber} leaves the original stream entry pending instead of acking it.
 */
final class LeavePendingOnRetireException extends RuntimeException {

    LeavePendingOnRetireException(Throwable cause) {
        super("re-queue publish failed", cause);
    }
}
