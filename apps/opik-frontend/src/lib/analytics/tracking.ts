const EVENT_PREFIX = "opik_";

export const OpikEvent = {
  ONBOARDING_AGENT_NAME_SUBMITTED: "opik_onboarding_agent_name_submitted",
  ONBOARDING_FIRST_TRACE_RECEIVED: "opik_onboarding_first_trace_received",
  ONBOARDING_SKIPPED: "opik_onboarding_skipped",
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
