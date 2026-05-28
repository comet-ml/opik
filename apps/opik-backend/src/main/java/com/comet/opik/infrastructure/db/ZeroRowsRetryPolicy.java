package com.comet.opik.infrastructure.db;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;

/**
 * Application-level retry for ClickHouse write operations that report zero written rows
 * while a non-empty result was expected (OPIK-6674).
 *
 * <p>This is intentionally a high-level, reactor-based policy rather than the v2 client's
 * built-in retry: {@code Client.Builder#setMaxRetries} / {@code retryOnFailures} only cover
 * transport-level {@code ClientFaultCause}s (connection errors, 503s). They have no concept
 * of "the INSERT succeeded at the HTTP layer but wrote 0 rows", which is the business-level
 * condition we need to detect and retry here.
 *
 * <p>Reusable across DAOs that issue {@code INSERT ... SELECT} on replicated tables; it is
 * decoupled from any specific config class — callers pass the resolved retry parameters.
 */
@Slf4j
public class ZeroRowsRetryPolicy {

    private final int maxAttempts;
    private final Duration minBackoff;
    private final Duration maxBackoff;

    public ZeroRowsRetryPolicy(int maxAttempts, @NonNull Duration minBackoff, @NonNull Duration maxBackoff) {
        this.maxAttempts = maxAttempts;
        this.minBackoff = minBackoff;
        this.maxBackoff = maxBackoff;
    }

    /**
     * Wraps {@code operation} so that a 0-row result (when {@code expectedRows > 0}) is surfaced
     * as {@link ZeroRowsWrittenException} and retried with exponential backoff. After the
     * attempts are exhausted the exception propagates instead of resolving to the truncated 0.
     *
     * <p>When {@code expectedRows <= 0} the guard is a no-op: 0 written rows is a valid outcome.
     */
    public Mono<Long> retryOnZeroRows(@NonNull Mono<Long> operation, long expectedRows,
            @NonNull String operationLabel) {
        if (expectedRows <= 0L) {
            return operation;
        }
        return operation
                .flatMap(actual -> actual == 0L
                        ? Mono.error(new ZeroRowsWrittenException(
                                "%s returned 0 rows; expected %d".formatted(operationLabel, expectedRows)))
                        : Mono.just(actual))
                .retryWhen(Retry.backoff(maxAttempts, minBackoff)
                        .maxBackoff(maxBackoff)
                        .filter(ZeroRowsWrittenException.class::isInstance)
                        .doBeforeRetry(rs -> log.warn("[{}] returned 0 rows; retrying (attempt {}/{})",
                                operationLabel, rs.totalRetries() + 1, maxAttempts))
                        .onRetryExhaustedThrow((spec, signal) -> signal.failure()));
    }
}
