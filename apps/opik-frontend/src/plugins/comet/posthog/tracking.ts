import posthog from "posthog-js";

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
    const instance = posthog.__loaded ? posthog : null;
    if (!instance) return;

    const prefixedEvent = event.startsWith(EVENT_PREFIX)
      ? event
      : EVENT_PREFIX + event;
    instance.capture(prefixedEvent, properties);
  } catch {
    // no-op when PostHog isn't available
  }
};
