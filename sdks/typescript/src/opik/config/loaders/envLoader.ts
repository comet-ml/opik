import { OpikConfig } from "../types";
import { filterUndefined } from "../utils/filterUndefined";
import { getProcessEnv } from "../utils/runtimeDetection";

/**
 * Parses environment variables into OpikConfig format
 */
function parseEnvVariables(
  env: Record<string, string | undefined>
): Partial<OpikConfig> {
  return filterUndefined({
    apiKey: env.OPIK_API_KEY,
    apiUrl: env.OPIK_URL_OVERRIDE,
    projectName: env.OPIK_PROJECT_NAME,
    workspaceName: env.OPIK_WORKSPACE,
    batchDelayMs: env.OPIK_BATCH_DELAY_MS
      ? Number(env.OPIK_BATCH_DELAY_MS)
      : undefined,
    holdUntilFlush:
      env.OPIK_HOLD_UNTIL_FLUSH === undefined
        ? undefined
        : ["1", "true", "yes"].includes(
            String(env.OPIK_HOLD_UNTIL_FLUSH).toLowerCase()
          ),
  });
}

/**
 * Load config from environment variables in Node.js environments
 */
export function loadFromEnvNode(): Partial<OpikConfig> {
  const env = getProcessEnv();
  return parseEnvVariables(env);
}

/**
 * Load config from environment variables in browser/edge environments
 */
export function loadFromEnvBrowser(): Partial<OpikConfig> {
  const env = getProcessEnv();
  return parseEnvVariables(env);
}
