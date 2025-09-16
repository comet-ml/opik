package com.comet.opik.utils;

import jakarta.ws.rs.ProcessingException;
import lombok.Getter;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
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

    private static boolean isRetriableException(Throwable throwable) {
        return switch (throwable) {
            case SocketException ex -> true;
            case TimeoutException ex -> true;
            case InterruptedIOException ex -> true;
            case RetryableHttpException ex -> true;
            case ProcessingException ex -> {
                var cause = ex.getCause();
                yield cause != null && isRetriableException(cause);
            }
            default -> false;
        };
    }

}
