import posthog from "posthog-js";

export enum OpikEvent {}

export function trackEvent(
  event: string,
  properties?: Record<string, unknown>,
) {
  try {
    if (posthog.__loaded) {
      posthog.capture(event, properties);
    }
  } catch {
    // PostHog may not be initialized (e.g., OSS mode)
  }
}
