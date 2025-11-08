import posthog from "posthog-js";

export const initPosthog = (key?: string, host?: string) => {
  if (!key || !host) return;

  posthog.init(key, {
    api_host: host,
    defaults: "2025-05-24",
    before_send: (event) => {
      // Include URL hash in pathname for tracking onboarding steps
      if (event && event.properties && window.location.hash) {
        event.properties.$pathname =
          window.location.pathname + window.location.hash;
      }
      return event;
    },
  });
};
