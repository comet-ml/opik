package com.comet.opik.utils;

import com.clickhouse.client.api.ClickHouseException;
import com.clickhouse.client.api.ServerException;
import jakarta.ws.rs.ProcessingException;
import lombok.Getter;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.core5.http.NoHttpResponseException;
import reactor.util.retry.Retry;
import reactor.util.retry.RetryBackoffSpec;

import java.io.InterruptedIOException;
import java.net.SocketException;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

@Slf4j
@UtilityClass
public class RetryUtils {

    public static RetryBackoffSpec handleConnectionError() {
        return Retry.backoff(3, Duration.ofMillis(100))
                .doBeforeRetry(retrySignal -> log.warn("Retrying due to: {}", retrySignal.failure().getMessage()))
                .onRetryExhaustedThrow((retryBackoffSpec, retrySignal) -> retrySignal.failure())
                .filter(throwable -> {
                    log.debug("Filtering for retry: {}", throwable.getMessage());

                    return SocketException.class.isAssignableFrom(throwable.getClass())
                            || (throwable instanceof IllegalStateException
                                    && throwable.getMessage().contains("Connection pool shut down"));
                });
    }

    public static RetryBackoffSpec handleOnDeadLocks() {
        return Retry.backoff(5, Duration.ofMillis(250))
                .maxBackoff(Duration.ofSeconds(2))
                .jitter(0.5) // Add jitter to reduce thundering herd effect
                .doBeforeRetry(retrySignal -> log.warn("Retrying due to database deadlock",
                        retrySignal.failure()))
                .onRetryExhaustedThrow((retryBackoffSpec, retrySignal) -> retrySignal.failure())
                .filter(throwable -> {
                    log.debug("Filtering for retry due to database deadlock", throwable);

                    return isDatabaseDeadlock(throwable);
                });
    }

    @Getter
    public static class RetryableHttpException extends RuntimeException {
        private final int statusCode;

        public RetryableHttpException(String message, int statusCode) {
            super(message);
            this.statusCode = statusCode;
        }

        public RetryableHttpException(String message, int statusCode, Throwable cause) {
            super(message, cause);
            this.statusCode = statusCode;
        }
    }

    /**
     * Retry specification for handling transient HTTP errors such as timeouts and connection issues.
     *
     * @param maxAttempts Maximum number of retry attempts.
     * @param minBackoff  Minimum backoff duration between retries.
     * @param maxBackoff  Maximum backoff duration between retries.
     * @return Configured RetryBackoffSpec instance.
     */
    public static RetryBackoffSpec handleHttpErrors(int maxAttempts, Duration minBackoff, Duration maxBackoff) {
        return Retry.backoff(maxAttempts, minBackoff)
                .maxBackoff(maxBackoff)
                .doBeforeRetry(retrySignal -> log.warn("Retrying due to: {}", retrySignal.failure().getMessage()))
                .onRetryExhaustedThrow((retryBackoffSpec, retrySignal) -> retrySignal.failure())
                .filter(throwable -> {
                    log.debug("Filtering for retry: {}", throwable.getMessage());
                    return isRetriableException(throwable);
                });
    }

    /**
     * Retry specification for ClickHouse operations. A retry reruns the whole operation, so for a write it may repeat
     * one that already landed: use it for writes only where a repeat collapses (e.g. a ReplacingMergeTree key).
     * <p>
     * Retried, when found anywhere in the cause chain: the transient errors {@link #handleHttpErrors} retries
     * ({@code SocketException}, {@code TimeoutException}, {@code InterruptedIOException},
     * {@code NoHttpResponseException}) and a {@code ClickHouseException} the v2 client flags
     * {@code isRetryable()}. Anything else, server errors the client does not flag included, fails at once. Walking
     * the chain is deliberate: the client wraps transport failures, so the top-level type alone would miss them.
     * Widening the set retries permanent failures through every backoff before they surface.
     * <p>
     * Retries log only the exception type and, for a server error, its code: ClickHouse messages can quote the
     * offending values.
     *
     * @param maxAttempts Maximum number of retry attempts.
     * @param minBackoff  Minimum backoff duration between retries.
     * @param maxBackoff  Maximum backoff duration between retries.
     * @return Configured RetryBackoffSpec instance.
     */
    public static RetryBackoffSpec handleClickHouseErrors(int maxAttempts, Duration minBackoff, Duration maxBackoff) {
        return Retry.backoff(maxAttempts, minBackoff)
                .maxBackoff(maxBackoff)
                .doBeforeRetry(retrySignal -> log.warn("Retrying ClickHouse operation, attempt '{}', after '{}'",
                        retrySignal.totalRetries() + 1, describe(retrySignal.failure())))
                .onRetryExhaustedThrow((retryBackoffSpec, retrySignal) -> retrySignal.failure())
                .filter(RetryUtils::isTransientClickHouseError);
    }

    private static boolean isTransientClickHouseError(Throwable throwable) {
        for (var cause = throwable; cause != null; cause = cause.getCause()) {
            if ((cause instanceof ClickHouseException clickHouseError && clickHouseError.isRetryable())
                    || isRetriableException(cause)) {
                return true;
            }
        }
        return false;
    }

    private static String describe(Throwable throwable) {
        return throwable instanceof ServerException serverError
                ? "%s(code=%d)".formatted(serverError.getClass().getSimpleName(), serverError.getCode())
                : throwable.getClass().getSimpleName();
    }

    private static boolean isDatabaseDeadlock(Throwable throwable) {
        if (throwable == null) {
            return false;
        }

        String className = throwable.getClass().getName();
        String message = throwable.getMessage();

        // Check for MySQL transaction rollback exception (deadlock)
        boolean isMySQLDeadlock = "com.mysql.cj.jdbc.exceptions.MySQLTransactionRollbackException".equals(className)
                && message != null && message.contains("Deadlock found");

        // If not a direct match, check the cause recursively
        return isMySQLDeadlock || (throwable.getCause() != null && isDatabaseDeadlock(throwable.getCause()));
    }

    private static boolean isRetriableException(Throwable throwable) {
        return switch (throwable) {
            // Network and timeout transient errors
            case SocketException ex -> true;
            case TimeoutException ex -> true;
            case InterruptedIOException ex -> true;
            // HTTP client transient errors (server closes connection, temporary unavailability)
            case NoHttpResponseException ex -> true;
            case RetryableHttpException ex -> true;
            // Wrapped exceptions: check cause recursively for transient errors
            case ProcessingException ex -> {
                var cause = ex.getCause();
                yield cause != null && isRetriableException(cause);
            }
            default -> false;
        };
    }

}
