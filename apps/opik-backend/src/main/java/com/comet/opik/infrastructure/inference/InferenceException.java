package com.comet.opik.infrastructure.inference;

import lombok.Getter;
import lombok.NonNull;

import java.io.IOException;
import java.time.Duration;

/**
 * A failed opik-inference call: the service's error envelope, or a local stand-in for a transport error or a body that
 * doesn't parse ({@code upstream_error}, retryable, as in the Python client).
 * <p>
 * The service's own message can quote the request (e.g. a validation error on an item), so it is kept out of
 * {@link #getMessage()} and must never be logged.
 */
@Getter
public class InferenceException extends RuntimeException {

    static final Duration MAX_RETRY_AFTER = Duration.ofSeconds(30);

    private final @NonNull String code;
    private final boolean retryable;
    private final int httpStatus;
    /** Null when the response had no usable {@code Retry-After}. */
    private final Duration retryAfter;
    private final String serviceMessage;

    InferenceException(@NonNull String code, boolean retryable, int httpStatus, Duration retryAfter,
            String serviceMessage, Throwable cause) {
        super("opik-inference call failed, code '%s', status '%s'".formatted(code, httpStatus), cause);
        this.code = code;
        this.retryable = retryable;
        this.httpStatus = httpStatus;
        this.retryAfter = retryAfter;
        this.serviceMessage = serviceMessage;
    }

    public boolean isQuotaExceeded() {
        return InferenceErrorCode.QUOTA_EXCEEDED.getValue().equals(code);
    }

    /**
     * A known code's retryability comes from the code, as in the Python client; a code this client doesn't know yet
     * falls back to the envelope's own flag.
     */
    static InferenceException fromResponse(int httpStatus, String retryAfterHeader, byte[] body) {
        var retryAfter = parseRetryAfter(retryAfterHeader);
        ErrorEnvelope envelope;
        try {
            envelope = InferenceJson.MAPPER.readValue(body, ErrorEnvelope.class);
        } catch (IOException | RuntimeException exception) {
            return upstreamError(httpStatus, retryAfter, exception);
        }
        var error = envelope.error();
        var retryable = InferenceErrorCode.find(error.code())
                .map(InferenceErrorCode::isRetryable)
                .orElse(error.retryable());
        return new InferenceException(error.code(), retryable, httpStatus, retryAfter, error.message(), null);
    }

    static InferenceException upstreamError(int httpStatus, Duration retryAfter, Throwable cause) {
        return new InferenceException(InferenceErrorCode.UPSTREAM_ERROR.getValue(), true, httpStatus, retryAfter,
                null, cause);
    }

    static InferenceException transport(@NonNull Throwable cause) {
        return upstreamError(InferenceErrorCode.UPSTREAM_ERROR.getHttpStatus(), null, cause);
    }

    /** Seconds, capped at 30 s. An HTTP-date or anything else unparseable is ignored, as in the Python client. */
    static Duration parseRetryAfter(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        double seconds;
        try {
            seconds = Double.parseDouble(header.trim());
        } catch (NumberFormatException exception) {
            return null;
        }
        if (!Double.isFinite(seconds) || seconds < 0) {
            return null;
        }
        return Duration.ofNanos((long) (Math.min(seconds, MAX_RETRY_AFTER.toSeconds()) * 1e9));
    }
}
