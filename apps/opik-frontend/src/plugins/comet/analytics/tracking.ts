const EVENT_PREFIX = "opik_";

// Follow-up tickets add event names here, e.g.:
//   ONBOARDING_AGENT_NAME_SUBMITTED: "opik_onboarding_agent_name_submitted",
export const OpikEvent = {} as const;

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
