package com.comet.opik.api.error;

import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Raised when an ingested {@code id} fails a UUID data-quality check — it is not a version 7 UUID, or
 * its embedded UUIDv7 timestamp falls outside the accepted ingestion window. It is an
 * {@link IllegalArgumentException} (the {@code id} argument is invalid) and carries a low-cardinality
 * {@link Reason} for observability.
 *
 * <p>{@link InvalidUUIDExceptionMapper} turns it into an HTTP 400 and records the reject-rate metric
 * tagged by endpoint and reason.
 */
@Getter
public class InvalidUUIDException extends IllegalArgumentException {

    @Getter
    @RequiredArgsConstructor
    public enum Reason {
        NOT_V7("not_v7"),
        TOO_OLD("too_old"),
        TOO_FAR_FUTURE("too_far_future");

        private final String value;
    }

    private final Reason reason;

    public InvalidUUIDException(@NonNull Reason reason, String message) {
        super(message);
        this.reason = reason;
    }
}
