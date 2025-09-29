import posthog from "posthog-js";

export const initPosthog = (key?: string, host?: string) => {
  if (!key || !host) return;

  posthog.init(key, {
    api_host: host,
    defaults: "2025-05-24",
  });
};
