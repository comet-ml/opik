package com.comet.opik.infrastructure.db;

/**
 * Raised when a ClickHouse {@code INSERT ... SELECT} reports zero written rows while the
 * caller expected a non-empty result — typically a cross-replica race where the SELECT
 * ran against a replica that did not yet have the source parts visible.
 *
 * <p>Used as the retry signal by {@link ZeroRowsRetryPolicy}. See OPIK-6674.
 */
public class ZeroRowsWrittenException extends RuntimeException {

    public ZeroRowsWrittenException(String message) {
        super(message);
    }
}
