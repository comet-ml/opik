package com.comet.opik.infrastructure.db;

import com.comet.opik.api.error.InvalidUUIDException;
import com.comet.opik.api.error.InvalidUUIDException.Reason;
import com.comet.opik.domain.retention.RetentionUtils;
import com.comet.opik.infrastructure.UuidValidationConfig;
import com.comet.opik.infrastructure.metrics.UuidValidationMetrics;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
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
 * <p>Three modes, derived from {@link UuidValidationConfig}:
 * <ul>
 *   <li><b>disabled</b> ({@code enabled=false}): a no-op kill-switch, ids are not checked.</li>
 *   <li><b>reject</b> ({@code enabled=true, auditOnly=false}): out-of-window ids are rejected via
 *   {@link InvalidUUIDException} (HTTP 400); the reject-rate metric is recorded by its
 *   {@link com.comet.opik.api.error.InvalidUUIDExceptionMapper}.</li>
 *   <li><b>audit</b> ({@code enabled=true, auditOnly=true}): out-of-window ids are counted
 *   ({@link UuidValidationMetrics}, tagged by workspace) and logged but NOT rejected — the shadow /
 *   log-only mode that surfaces offenders without breaking ingestion (OPIK-7402).</li>
 * </ul>
 */
@Slf4j
@Singleton
public class UuidV7TimestampValidator {

    private final boolean enabled;
    private final boolean auditOnly;
    private final Duration window;
    private final UuidValidationMetrics metrics;

    @Inject
    public UuidV7TimestampValidator(@NonNull @Config("uuidValidation") UuidValidationConfig config,
            @NonNull UuidValidationMetrics metrics) {
        this.enabled = config.enabled();
        this.auditOnly = config.auditOnly();
        this.window = config.window().toJavaDuration();
        this.metrics = metrics;
    }

    /**
     * Rejects (HTTP 400) an id whose embedded timestamp is out of window (too old or too far in the
     * future) in reject mode; counts + logs it without rejecting in audit mode; no-op when validation is
     * disabled or the id is acceptable. Used by the creation path. {@code resource} (trace/span) and
     * {@code workspaceId} are attached to the audit metric.
     */
    public void validate(@NonNull UUID id, @NonNull String resource, String workspaceId) {
        evaluate(id).ifPresent(rejection -> handle(rejection, resource, workspaceId));
    }

    /**
     * Like {@link #validate}, but only acts when the embedded timestamp is too far in the future. Old
     * ids are accepted, so updating a long-lived entity (e.g. created months ago) is never flagged. Used
     * by the update path.
     */
    public void validateNotInFuture(@NonNull UUID id, @NonNull String resource, String workspaceId) {
        evaluate(id)
                .filter(rejection -> rejection.getLeft() == Reason.TOO_FAR_FUTURE)
                .ifPresent(rejection -> handle(rejection, resource, workspaceId));
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

    /**
     * In audit mode, records the per-workspace reject-rate metric and logs the would-be rejection, then
     * lets the write through. Otherwise throws {@link InvalidUUIDException} (HTTP 400).
     */
    private void handle(Pair<Reason, Instant> rejection, String resource, String workspaceId) {
        var reason = rejection.getLeft();
        var timestamp = rejection.getRight();
        if (auditOnly) {
            metrics.recordAudit(reason.getValue(), resource, workspaceId);
            // Keep a fixed, searchable prefix ("UUIDv7 audit: would-reject id ...") and append the
            // variable fields at the end, so log searches match on the message rather than the values.
            log.info(
                    "UUIDv7 audit: would-reject id, embedded timestamp '{}' outside window '{}', reason '{}', resource '{}', workspace '{}'",
                    timestamp, window, reason.getValue(), resource, workspaceId);
            return;
        }
        throw new InvalidUUIDException(reason,
                "id with timestamp '%s' must be in the allowed ingestion window of '%s' around now, reason '%s'"
                        .formatted(timestamp, window, reason.getValue()));
    }
}
