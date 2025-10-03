/**
 * Opik environment variable names
 * Use these constants instead of hardcoding strings to ensure consistency
 */

export const OPIK_ENV_VARS = {
  /** API key for authentication */
  API_KEY: 'OPIK_API_KEY',

  /** Base URL override (e.g., http://localhost:5173/api) */
  URL_OVERRIDE: 'OPIK_URL_OVERRIDE',

  /** Workspace name */
  WORKSPACE: 'OPIK_WORKSPACE',

  /** Project name (defaults to "Default Project" if not set) */
  PROJECT_NAME: 'OPIK_PROJECT_NAME',
} as const;

/**
 * All Opik environment variable names as an array
 * Useful for validation, display, or batch operations
 */
export const OPIK_ENV_VAR_NAMES = Object.values(OPIK_ENV_VARS);

/**
 * Type-safe helper to check if a string is a valid Opik env var name
 */
type OpikEnvVar = (typeof OPIK_ENV_VARS)[keyof typeof OPIK_ENV_VARS];
export function isOpikEnvVar(key: string): key is OpikEnvVar {
  return OPIK_ENV_VAR_NAMES.includes(key as OpikEnvVar);
}

/**
 * Default values for Opik environment variables
 */
export const OPIK_ENV_VAR_DEFAULTS = {
  [OPIK_ENV_VARS.PROJECT_NAME]: 'Default Project',
} as const;

/**
 * Human-readable descriptions for each environment variable
 */
export const OPIK_ENV_VAR_DESCRIPTIONS = {
  [OPIK_ENV_VARS.API_KEY]: 'Your Opik API key for authentication',
  [OPIK_ENV_VARS.URL_OVERRIDE]:
    'Base URL for Opik API (Cloud: https://www.comet.com/opik/api, Local: http://localhost:5173/api)',
  [OPIK_ENV_VARS.WORKSPACE]: 'Your Opik workspace name',
  [OPIK_ENV_VARS.PROJECT_NAME]:
    'Project name for organizing traces (defaults to "Default Project")',
} as const;
