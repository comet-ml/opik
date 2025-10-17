import posthog from "posthog-js";

export const initPosthog = (key?: string, host?: string) => {
  if (!key || !host) return;

  posthog.init(key, {
    api_host: host,
    ui_host: "https://us.posthog.com",
    defaults: "2025-05-24",
  });
};
