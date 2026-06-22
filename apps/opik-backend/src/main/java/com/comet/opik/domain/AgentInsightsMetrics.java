package com.comet.opik.domain;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.metrics.Meter;
import lombok.experimental.UtilityClass;

/**
 * Shared OpenTelemetry instruments and attribute keys for the Agent Insights pipeline (OPIK-7052).
 * The instruments are built once here from a single meter so every path emits under the same
 * instrumentation scope; the enqueue/error counters in particular are shared by the daily sweep
 * ({@code AgentInsightsReportJob}) and the manual {@code /trigger} ({@code AgentInsightsJobService}).
 */
@UtilityClass
public class AgentInsightsMetrics {

    public static final String METER_NAME = "opik.agent_insights";

    private static final Meter METER = GlobalOpenTelemetry.get().getMeter(METER_NAME);

    public static final LongHistogram SWEEP_DURATION_MS = METER
            .histogramBuilder("opik_agent_insights_sweep_duration_ms")
            .setDescription("Wall time of the daily Agent Insights sweep; _count by outcome gives run totals")
            .setUnit("ms")
            .ofLongs()
            .build();

    public static final LongCounter REPORTS_ENQUEUED = METER
            .counterBuilder("opik_agent_insights_reports_enqueued_total")
            .setDescription("Report runs enqueued onto the trigger queue, by trigger source")
            .build();

    public static final LongCounter TRIGGER_ERRORS = METER
            .counterBuilder("opik_agent_insights_trigger_errors_total")
            .setDescription("Failures enqueueing a report run, by trigger source")
            .build();

    public static final LongCounter REPORTS_TRIGGERED = METER
            .counterBuilder("opik_agent_insights_reports_triggered_total")
            .setDescription("Report trigger calls to the platform, by outcome")
            .build();

    public static final LongCounter REPORTS_RECEIVED = METER
            .counterBuilder("opik_agent_insights_reports_received_total")
            .setDescription("Generated reports received on the reporting endpoint")
            .build();

    public static final LongCounter ISSUES_REPORTED = METER
            .counterBuilder("opik_agent_insights_issues_reported_total")
            .setDescription("Issues persisted from generated reports")
            .build();

    public static final AttributeKey<String> TRIGGER = AttributeKey.stringKey("trigger");
    public static final AttributeKey<String> OUTCOME = AttributeKey.stringKey("outcome");

    public static final String SCHEDULED = "scheduled";
    public static final String MANUAL = "manual";
    public static final String SUCCESS = "success";
    public static final String FAILURE = "failure";

    // Cached attribute sets for the fixed label combinations emitted on hot paths (sweep/trigger/enqueue),
    // so the counters/histogram reuse one immutable Attributes instance per outcome instead of allocating.
    public static final Attributes OUTCOME_SUCCESS = Attributes.of(OUTCOME, SUCCESS);
    public static final Attributes OUTCOME_FAILURE = Attributes.of(OUTCOME, FAILURE);
    public static final Attributes TRIGGER_SCHEDULED = Attributes.of(TRIGGER, SCHEDULED);
    public static final Attributes TRIGGER_MANUAL = Attributes.of(TRIGGER, MANUAL);
}
