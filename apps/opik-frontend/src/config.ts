export const SENTRY_DSN = import.meta.env.VITE_SENTRY_DSN;
export const SENTRY_ENABLED = import.meta.env.VITE_SENTRY_ENABLED === "true";
export const SENTRY_MODE = import.meta.env.VITE_SENTRY_ENVIRONMENT;

export const IS_SENTRY_ENABLED = Boolean(SENTRY_DSN && SENTRY_ENABLED);

// Set VITE_WRITE_ACTIONS_ENABLED=false to deploy Opik in read-only mode.
// All create/edit/delete UI will be hidden; read and export operations remain available.
export const WRITE_ACTIONS_ENABLED =
  import.meta.env.VITE_WRITE_ACTIONS_ENABLED !== "false";
