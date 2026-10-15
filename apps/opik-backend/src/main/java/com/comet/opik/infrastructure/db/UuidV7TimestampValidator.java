package com.comet.opik.infrastructure.db;

import com.comet.opik.api.error.InvalidUUIDException;
import com.comet.opik.api.error.InvalidUUIDException.Reason;
import com.comet.opik.domain.retention.RetentionUtils;
import com.comet.opik.infrastructure.UuidValidationConfig;
import com.comet.opik.infrastructure.metrics.ErrorMetricsResolver;
import com.comet.opik.infrastructure.metrics.UuidValidationMetrics;
import com.google.common.annotations.VisibleForTesting;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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
 *
 * <h2>Workspace-scoped bypass</h2>
 *
 * <p>Orthogonal to the three modes, an allow-listed workspace is validated against
 * {@link UuidValidationConfig#bypassWindow()} instead of {@code window}, so that internal demo
 * workspaces can build projects spanning time: the project timeline buckets on the id-embedded
 * timestamp, so such a demo must mint ids with spread-out timestamps, which the default window rejects
 * (OPIK-7794). The bypass stays bounded — an allow-listed workspace is rejected beyond the override
 * window like anyone else — so the unbounded-partition risk never reopens.
 *
 * <p>The allow-list is read from the {@code OPIK_UUID_VALIDATION_BYPASS_WORKSPACES} environment variable
 * rather than from {@code config.yml}, keeping which workspaces are trusted out of the shipped
 * configuration. Absent or empty means no bypass at all, identical to the behavior without this feature.
 * Parsing is deliberately lopsided: it only ever drops entries, never throws, and falls back to an empty
 * set, so no malformed value can grant an unintended bypass. Granting one wrongly is a partition risk;
 * denying one wrongly only breaks a demo.
 */
@Slf4j
@Singleton
public class UuidV7TimestampValidator {

    /**
     * Environment variable naming the workspaces exempted from the default window.
     */
    private static final String BYPASS_WORKSPACES_ENV = "OPIK_UUID_VALIDATION_BYPASS_WORKSPACES";
    /**
     * Separates workspaces within {@value #BYPASS_WORKSPACES_ENV}.
     */
    private static final String ENTRY_DELIMITER = ",";
    /**
     * Caps the allow-list size, so operator-supplied input cannot grow without limit.
     */
    private static final int MAX_BYPASS_WORKSPACES = 100;
    /**
     * Conservative workspace-id shape, bounding length and charset in one pattern. Deliberately not a
     * strict UUID match, since a workspace id is not guaranteed to be one, but tight enough to reject
     * whitespace, control characters and injection payloads in operator-supplied input.
     */
    private static final Pattern BYPASS_WORKSPACE_PATTERN = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private final boolean enabled;
    private final boolean auditOnly;
    private final Duration window;
    /**
     * The window allow-listed workspaces are validated against: {@link UuidValidationConfig#bypassWindow()}
     * when it widens {@link #window}, and {@link #window} itself otherwise. A configured value at or below
     * the default widens nothing, and below it would make an allow-listed workspace stricter than everyone
     * else, so it is ignored and reported at startup instead.
     */
    private final Duration bypassWindow;
    /**
     * The workspaces exempted from {@link #window}, parsed once from {@value #BYPASS_WORKSPACES_ENV}.
     * Empty unless that variable names at least one valid workspace; while empty, the bypass is off.
     */
    private final Set<String> bypassWorkspaces;
    private final UuidValidationMetrics metrics;

    @Inject
    public UuidV7TimestampValidator(@NonNull @Config("uuidValidation") UuidValidationConfig config,
            @NonNull UuidValidationMetrics metrics) {
        this(config, metrics, System.getenv(BYPASS_WORKSPACES_ENV));
    }

    /**
     * Takes the raw allow-list value instead of reading the environment, which tests cannot set.
     */
    @VisibleForTesting
    UuidV7TimestampValidator(@NonNull UuidValidationConfig config, @NonNull UuidValidationMetrics metrics,
            String rawBypassWorkspaces) {
        this.enabled = config.enabled();
        this.auditOnly = config.auditOnly();
        this.window = config.window().toJavaDuration();
        var configuredBypassWindow = config.bypassWindow().toJavaDuration();
        if (configuredBypassWindow.compareTo(this.window) > 0) {
            this.bypassWindow = configuredBypassWindow;
        } else {
            this.bypassWindow = this.window;
            log.info("UUIDv7 validation: bypass window does not widen the default one, bypass '{}', window '{}'",
                    configuredBypassWindow, this.window);
        }
        this.metrics = metrics;
        this.bypassWorkspaces = parseBypassWorkspaces(rawBypassWorkspaces);
        if (!this.bypassWorkspaces.isEmpty()) {
            log.info("UUIDv7 validation: workspaces allow-listed for the bypass window, count '{}', window '{}'",
                    this.bypassWorkspaces.size(), this.bypassWindow);
        }
    }

    /**
     * Rejects (HTTP 400) an id whose embedded timestamp is out of window (too old or too far in the
     * future) in reject mode; counts + logs it without rejecting in audit mode; no-op when validation is
     * disabled or the id is acceptable. Used by the creation path. {@code resource} (trace/span) and
     * {@code workspaceId} are attached to the audit metric.
     */
    public void validate(@NonNull UUID id, String resource, String workspaceId) {
        evaluate(id).ifPresent(rejection -> handle(rejection, resource, workspaceId));
    }

    /**
     * Like {@link #validate}, but only acts when the embedded timestamp is too far in the future. Old
     * ids are accepted, so updating a long-lived entity (e.g. created months ago) is never flagged. Used
     * by the update path.
     */
    public void validateNotInFuture(@NonNull UUID id, String resource, String workspaceId) {
        evaluate(id)
                .filter(rejection -> rejection.getLeft() == Reason.TOO_FAR_FUTURE)
                .ifPresent(rejection -> handle(rejection, resource, workspaceId));
    }

    /**
     * Pure validation decision: returns the rejection reason paired with the id's embedded timestamp if
     * it falls outside the window, or empty if it is acceptable (or validation is disabled). The
     * workspace-scoped bypass is resolved in {@link #handle} instead, so it is only consulted for ids
     * that would actually be rejected.
     */
    private Optional<Pair<Reason, Instant>> evaluate(UUID id) {
        if (!enabled) {
            return Optional.empty();
        }
        var timestamp = RetentionUtils.extractInstant(id);
        return classify(timestamp, window).map(reason -> Pair.of(reason, timestamp));
    }

    /**
     * Which bound {@code timestamp} breaks for the given window, or empty when it is inside it.
     */
    private Optional<Reason> classify(Instant timestamp, Duration window) {
        var now = Instant.now();
        if (timestamp.isBefore(now.minus(window))) {
            return Optional.of(Reason.TOO_OLD);
        }
        if (timestamp.isAfter(now.plus(window))) {
            return Optional.of(Reason.TOO_FAR_FUTURE);
        }
        return Optional.empty();
    }

    /**
     * Resolves a would-be rejection. An allow-listed workspace whose id still fits the wider bypass
     * window is let through and counted, so demo ingestion stays observable; beyond it the rejection is
     * reported against the bypass window. In audit mode, records the per-workspace reject-rate metric and
     * logs the would-be rejection, then lets the write through. Otherwise throws
     * {@link InvalidUUIDException} (HTTP 400).
     */
    private void handle(Pair<Reason, Instant> rejection, String resource, String workspaceId) {
        var reason = rejection.getLeft();
        var timestamp = rejection.getRight();
        var bypassed = isBypassWorkspace(workspaceId);
        if (bypassed && classify(timestamp, bypassWindow).isEmpty()) {
            metrics.recordBypass(reason.getValue(), resource, workspaceId);
            log.info(
                    "UUIDv7 bypass: accepted id inside the bypass window, timestamp '{}', window '{}', reason '{}', resource '{}', workspace '{}'",
                    timestamp, bypassWindow, reason.getValue(), resource, workspaceId);
            return;
        }
        var effectiveWindow = bypassed ? bypassWindow : window;
        if (auditOnly) {
            metrics.recordAudit(reason.getValue(), resource, workspaceId);
            // Keep a fixed, searchable prefix ("UUIDv7 audit: would-reject id ...") and append the
            // variable fields at the end, so log searches match on the message rather than the values.
            log.info(
                    "UUIDv7 audit: would-reject id, embedded timestamp '{}' outside window '{}', reason '{}', resource '{}', workspace '{}'",
                    timestamp, effectiveWindow, reason.getValue(), resource, workspaceId);
            return;
        }
        throw new InvalidUUIDException(reason,
                "id with timestamp '%s' must be in the allowed ingestion window of '%s' around now, reason '%s'"
                        .formatted(timestamp, effectiveWindow, reason.getValue()));
    }

    /**
     * Exact match against the allow-list — no case-normalization or fuzzy matching, since normalizing
     * could only ever broaden a match, which is the unsafe direction.
     */
    private boolean isBypassWorkspace(String workspaceId) {
        return workspaceId != null && bypassWorkspaces.contains(workspaceId);
    }

    /**
     * Parses the comma-separated allow-list into the deduplicated set of workspaces, dropping anything
     * that doesn't pass: blanks, entries outside {@link #BYPASS_WORKSPACE_PATTERN}, the
     * {@link ErrorMetricsResolver#UNKNOWN} placeholder, and entries beyond
     * {@link #MAX_BYPASS_WORKSPACES}. A faulty entry never invalidates the valid ones, and any unexpected
     * failure yields an empty set — the feature off — so parsing can neither fail startup nor grant a
     * bypass that wasn't configured.
     */
    private Set<String> parseBypassWorkspaces(String rawValue) {
        if (StringUtils.isBlank(rawValue)) {
            return Set.of();
        }
        try {
            var workspaces = Arrays.stream(StringUtils.split(rawValue, ENTRY_DELIMITER))
                    .map(String::trim)
                    .filter(StringUtils::isNotBlank)
                    .filter(this::isAllowedWorkspace)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            if (workspaces.size() > MAX_BYPASS_WORKSPACES) {
                log.warn("UUIDv7 validation: dropping bypass workspaces beyond the limit, limit '{}', parsed '{}'",
                        MAX_BYPASS_WORKSPACES, workspaces.size());
            }
            return workspaces.stream().limit(MAX_BYPASS_WORKSPACES).collect(Collectors.toUnmodifiableSet());
        } catch (Exception exception) {
            log.warn("UUIDv7 validation: failed to parse the bypass workspaces, variable '{}'",
                    BYPASS_WORKSPACES_ENV, exception);
            return Set.of();
        }
    }

    /**
     * Whether one trimmed entry may be allow-listed, warning when it may not.
     *
     * <p>{@link ErrorMetricsResolver#UNKNOWN} is rejected even though it matches the pattern: it is the
     * placeholder passed in by every caller that carries no workspace, so allow-listing it would widen the
     * window for all of them, in every workspace, at once.
     *
     * <p>A malformed entry is never logged: it is operator-supplied and may carry control characters or
     * log-injection payloads, so only its length is reported.
     */
    private boolean isAllowedWorkspace(String workspaceId) {
        if (ErrorMetricsResolver.UNKNOWN.equals(workspaceId)) {
            log.warn("UUIDv7 validation: dropping the reserved bypass workspace, workspace '{}'",
                    ErrorMetricsResolver.UNKNOWN);
            return false;
        }
        if (BYPASS_WORKSPACE_PATTERN.matcher(workspaceId).matches()) {
            return true;
        }
        log.warn("UUIDv7 validation: dropping malformed bypass workspace, length '{}'", workspaceId.length());
        return false;
    }
}
