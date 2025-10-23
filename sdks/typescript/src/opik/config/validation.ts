import { OpikConfig } from "./types";
import { isCloud } from "./utils/cloudDetection";

/**
 * Validates the configuration object
 * Throws errors for invalid or incomplete configurations
 *
 * @throws {Error} If apiUrl is not set
 * @throws {Error} If apiKey is missing for cloud hosts
 * @throws {Error} If workspaceName is missing for cloud hosts
 */
export function validateConfig(config: OpikConfig): OpikConfig {
  if (!config.apiUrl) {
    throw new Error("OPIK_URL_OVERRIDE is not set");
  }

  const isCloudHost = isCloud(config.apiUrl);

  if (isCloudHost && !config.apiKey) {
    throw new Error("OPIK_API_KEY is not set");
  }

  if (isCloudHost && !config.workspaceName) {
    throw new Error("OPIK_WORKSPACE is not set");
  }

  return config;
}
