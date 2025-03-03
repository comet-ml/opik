export const SENTRY_DSN = import.meta.env.VITE_SENTRY_DSN;
export const SENTRY_ENABLED = import.meta.env.VITE_SENTRY_ENABLED === "true";
export const SENTRY_MODE = import.meta.env.VITE_SENTRY_ENVIRONMENT;

export const IS_SENTRY_ENABLED = Boolean(SENTRY_DSN && SENTRY_ENABLED);
