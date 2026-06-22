package com.comet.opik.domain;

import io.opentelemetry.api.common.AttributeKey;
import lombok.experimental.UtilityClass;

/**
 * Shared OpenTelemetry instrument names and attribute keys for the Agent Insights pipeline (OPIK-7052).
 * The enqueue/error counters in particular are emitted from both the daily sweep
 * ({@code AgentInsightsReportJob}) and the manual {@code /trigger} ({@code AgentInsightsJobService}), so
 * keeping the names here makes that shared usage explicit instead of duplicating string literals.
 */
@UtilityClass
public class AgentInsightsMetrics {

    public static final String METER_NAME = "opik.agent_insights";

    public static final String SWEEP_DURATION = "opik_agent_insights_sweep_duration_ms";
    public static final String SWEEP_DURATION_DESC = "Wall time of the daily Agent Insights sweep; _count by outcome gives run totals";

    public static final String REPORTS_ENQUEUED = "opik_agent_insights_reports_enqueued_total";
    public static final String REPORTS_ENQUEUED_DESC = "Report runs enqueued onto the trigger queue, by trigger source";

    public static final String TRIGGER_ERRORS = "opik_agent_insights_trigger_errors_total";
    public static final String TRIGGER_ERRORS_DESC = "Failures enqueueing a report run, by trigger source";

    public static final String REPORTS_TRIGGERED = "opik_agent_insights_reports_triggered_total";
    public static final String REPORTS_TRIGGERED_DESC = "Report trigger calls to the platform, by outcome";

    public static final String REPORTS_RECEIVED = "opik_agent_insights_reports_received_total";
    public static final String REPORTS_RECEIVED_DESC = "Generated reports received on the reporting endpoint";

    public static final String ISSUES_REPORTED = "opik_agent_insights_issues_reported_total";
    public static final String ISSUES_REPORTED_DESC = "Issues persisted from generated reports";

    public static final AttributeKey<String> TRIGGER = AttributeKey.stringKey("trigger");
    public static final AttributeKey<String> OUTCOME = AttributeKey.stringKey("outcome");

    public static final String SCHEDULED = "scheduled";
    public static final String MANUAL = "manual";
    public static final String SUCCESS = "success";
    public static final String FAILURE = "failure";
}
