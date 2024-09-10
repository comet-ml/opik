import initSnippet from "./snippet";

export const initAnalytics = (writeKey?: string) => {
  if (writeKey) {
    initSnippet(writeKey);
  }
};

export const trackEvent = (
  name: string,
  properties: Record<string, unknown>,
) => {
  if (window.analytics) {
    window.analytics.track(name, properties);
  }
};
