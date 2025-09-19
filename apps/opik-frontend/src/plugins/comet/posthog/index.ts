import posthog from "posthog-js";

export const initPosthog = (key?: string, host?: string) => {
  if (!key || !host) return;

  posthog.init(key, {
    api_host: host,
    capture_pageview: false,
    capture_pageleave: true,
    defaults: "2025-05-24",
  });
};
