package com.comet.opik.infrastructure.metrics;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import jakarta.inject.Singleton;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;

import static com.comet.opik.infrastructure.metrics.ErrorMetricsResolver.UNKNOWN;
import static com.comet.opik.infrastructure.metrics.ErrorMetricsResolver.WORKSPACE_ID_KEY;

/**
 * Records the {@code opik.ingestion.uuid_v7.rejected} counter for the audit (shadow / log-only) mode
 * of {@link com.comet.opik.infrastructure.db.UuidV7TimestampValidator}, broken down by {@code
 * workspace_id}, the failed-check {@code reason} and the {@code resource} (trace/span).
 * <p>
 * This is the freshness mechanism for the UUIDv7 re-enablement campaign (OPIK-7402): with the validator
 * in audit mode, out-of-window ids are counted here per workspace but not rejected, so offending clients
 * (e.g. the buggy LiteLLM native Opik integration) surface in real time without breaking ingestion.
 * <p>
 * It shares the counter name and description with {@link
 * com.comet.opik.api.error.InvalidUUIDExceptionMapper}, which records the same counter on the reject
 * path. The {@code mode} label distinguishes the two: {@code audit} here vs {@code reject} on the
 * enforced path. Only the audit path carries {@code workspace_id}, because the reject path runs in the
 * exception mapper where the request-scoped workspace is not threaded (a Story-2 follow-up).
 * <p>
 * The {@code workspace_id} label is bounded by the set of clients actually emitting out-of-window ids
 * (a small cohort), so it does not inflate metric cardinality the way an unconditional per-workspace
 * label would.
 */
@Singleton
public class UuidValidationMetrics {

    private static final String METRIC_NAMESPACE = "opik.ingestion";
    private static final String COUNTER_DESCRIPTION = "Number of writes rejected because the id failed UUIDv7 ingestion validation";

    public static final String MODE_AUDIT = "audit";

    public static final AttributeKey<String> MODE_KEY = AttributeKey.stringKey("mode");
    public static final AttributeKey<String> REASON_KEY = AttributeKey.stringKey("reason");
    public static final AttributeKey<String> RESOURCE_KEY = AttributeKey.stringKey("resource");

    private final LongCounter rejectedCounter;

    public UuidValidationMetrics() {
        Meter meter = GlobalOpenTelemetry.get().getMeter(METRIC_NAMESPACE);
        this.rejectedCounter = meter
                .counterBuilder("%s.uuid_v7.rejected".formatted(METRIC_NAMESPACE))
                .setDescription(COUNTER_DESCRIPTION)
                .build();
    }

    /**
     * Records one audit-mode detection: an id that would have been rejected (out of window) but was let
     * through. {@code reason} is the low-cardinality {@link
     * com.comet.opik.api.error.InvalidUUIDException.Reason} value, {@code resource} the entity kind
     * (trace/span), {@code workspaceId} the emitting workspace (falls back to {@code unknown}).
     */
    public void recordAudit(@NonNull String reason, @NonNull String resource, String workspaceId) {
        rejectedCounter.add(1, Attributes.builder()
                .put(MODE_KEY, MODE_AUDIT)
                .put(REASON_KEY, reason)
                .put(RESOURCE_KEY, StringUtils.defaultIfBlank(resource, UNKNOWN))
                .put(WORKSPACE_ID_KEY, StringUtils.defaultIfBlank(workspaceId, UNKNOWN))
                .build());
    }
}
