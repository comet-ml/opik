import { OpikConfig } from "./types";

/**
 * Default path for the Opik configuration file
 */
export const CONFIG_FILE_PATH_DEFAULT = "~/.opik.config";

/**
 * Default configuration values for Opik SDK
 */
export const DEFAULT_CONFIG: Required<Omit<OpikConfig, "requestOptions">> = {
  apiKey: "",
  apiUrl: "https://www.comet.com/opik/api",
  projectName: "Default Project",
  workspaceName: "default",
  batchDelayMs: 300,
  holdUntilFlush: false,
};
