package com.comet.opik.infrastructure.db;

import com.comet.opik.api.error.InvalidUUIDException;
import com.comet.opik.api.error.InvalidUUIDException.Reason;
import com.comet.opik.domain.retention.RetentionUtils;
import com.comet.opik.infrastructure.UuidValidationConfig;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import org.apache.commons.lang3.tuple.Pair;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Validates that an ingested {@code id}'s embedded UUIDv7 timestamp falls within
 * {@code [now() - window, now() + window]}.
 *
 * <p>The embedded timestamp is what ClickHouse uses to compute the partition, so a row claiming
 * a far-future timestamp would land in a partition that retention never reaches and corrupt
 * the partition layout. Bounding the timestamp at ingestion makes broken clients surface
 * as HTTP 400 instead of silent partition corruption.
 *
 * <p>The validation is intentionally version-agnostic on the timestamp bits: it checks the top 48
 * bits regardless of UUID version, because those bits drive partition placement on every write.
 *
 * <p>When {@code enabled} is false the validator is a no-op, acting as an operational kill-switch.
 * Rejections are signaled via {@link InvalidUUIDException}; the reject-rate metric is recorded by its
 * {@link com.comet.opik.api.error.InvalidUUIDExceptionMapper}.
 */
@Singleton
public class UuidV7TimestampValidator {

    private final boolean enabled;
    private final Duration window;

    @Inject
    public UuidV7TimestampValidator(@NonNull @Config("uuidValidation") UuidValidationConfig config) {
        this.enabled = config.enabled();
        this.window = config.window().toJavaDuration();
    }

    /**
     * Throws {@link InvalidUUIDException} (HTTP 400) when the id's embedded timestamp is out of window
     * (too old or too far in the future). No-op when validation is disabled or the id is acceptable.
     * Used by the creation path.
     */
    public void validate(@NonNull UUID id) {
        evaluate(id).ifPresent(this::reject);
    }

    /**
     * Throws {@link InvalidUUIDException} (HTTP 400) only when the id's embedded timestamp is too far in
     * the future. Old ids are accepted, so updating a long-lived entity (e.g. created months ago) is
     * never rejected. Used by the update path.
     */
    public void validateNotInFuture(@NonNull UUID id) {
        evaluate(id)
                .filter(rejection -> rejection.getLeft() == Reason.TOO_FAR_FUTURE)
                .ifPresent(this::reject);
    }

    /**
     * Pure validation decision: returns the rejection reason paired with the id's embedded timestamp if
     * it falls outside the window, or empty if it is acceptable (or validation is disabled).
     */
    private Optional<Pair<Reason, Instant>> evaluate(UUID id) {
        if (!enabled) {
            return Optional.empty();
        }
        var timestamp = RetentionUtils.extractInstant(id);
        var now = Instant.now();
        if (timestamp.isBefore(now.minus(window))) {
            return Optional.of(Pair.of(Reason.TOO_OLD, timestamp));
        }
        if (timestamp.isAfter(now.plus(window))) {
            return Optional.of(Pair.of(Reason.TOO_FAR_FUTURE, timestamp));
        }
        return Optional.empty();
    }

    private void reject(Pair<Reason, Instant> rejection) {
        var reason = rejection.getLeft();
        var timestamp = rejection.getRight();
        throw new InvalidUUIDException(reason,
                "id with timestamp '%s' must be in the allowed ingestion window of '%s' around now, reason '%s'"
                        .formatted(timestamp, window, reason.getValue()));
    }
}
