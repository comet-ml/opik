const EVENT_PREFIX = "opik_";

export const OpikEvent = {
  ONBOARDING_INTENT_SELECTED: "opik_onboarding_intent_selected",
  ONBOARDING_AGENT_NAME_SUBMITTED: "opik_onboarding_agent_name_submitted",
  ONBOARDING_FIRST_TRACE_RECEIVED: "opik_onboarding_first_trace_received",
  ONBOARDING_SKIPPED: "opik_onboarding_skipped",
  EVAL_SUITE_UI_CONFIGURED: "opik_eval_suite_ui_configured",
  AGENT_CONFIG_UI_DEPLOYED: "opik_agent_config_ui_deployed",
  OPTIMIZATION_WIZARD_STARTED: "opik_optimization_wizard_started",
  FILTER_APPLIED: "opik_filter_applied",
  FILTER_REMOVED: "opik_filter_removed",
  FILTER_PINNED: "opik_filter_pinned",
  FILTER_UNPINNED: "opik_filter_unpinned",
  FILTER_DIALOG_OPENED: "opik_filter_dialog_opened",
  FILTER_DIALOG_CLOSED_WITHOUT_SELECTION:
    "opik_filter_dialog_closed_without_selection",
  FILTERS_ACTIVE_COUNT: "opik_filters_active_count",
  PINNED_FILTERS_COUNT: "opik_pinned_filters_count",
  EXPLAIN_CLICKED: "opik_explain_clicked",
  EXPLAIN_COMPLETED: "opik_explain_completed",
  EXPLAIN_ERRORED: "opik_explain_errored",
  EXPLAIN_CONTINUE_CLICKED: "opik_explain_continue_clicked",
  EXPLAIN_RETRIED: "opik_explain_retried",
  QUICK_FILTER_APPLIED: "opik_quick_filter_applied",
} as const;

type OpikEventValues = (typeof OpikEvent)[keyof typeof OpikEvent];
export type OpikEventName = OpikEventValues extends never
  ? string
  : OpikEventValues;

export const trackEvent = (
  event: OpikEventName,
  properties?: Record<string, unknown>,
) => {
  try {
    if (!window.analytics) return;

    const prefixedEvent = event.startsWith(EVENT_PREFIX)
      ? event
      : EVENT_PREFIX + event;

    const environment =
      window.environmentVariablesOverwrite?.OPIK_ANALYTICS_ENVIRONMENT;
    const enrichedProperties = environment
      ? { ...properties, environment }
      : properties;

    window.analytics.track(prefixedEvent, enrichedProperties);
  } catch {
    // no-op when Segment isn't available
  }
};
