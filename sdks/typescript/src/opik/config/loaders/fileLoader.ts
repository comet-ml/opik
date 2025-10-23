import { logger } from "@/utils/logger";
import { OpikConfig } from "../types";
import { CONFIG_FILE_PATH_DEFAULT } from "../constants";
import { filterUndefined } from "../utils/filterUndefined";
import { getProcessEnv } from "../utils/runtimeDetection";
import fs from "fs";
import ini from "ini";

/**
 * Load config from file in Node.js environments
 * Uses synchronous file reading for compatibility
 */
export function loadFromConfigFileNode(): Partial<OpikConfig> {
  const env = getProcessEnv();
  const configFilePath = env.OPIK_CONFIG_PATH || CONFIG_FILE_PATH_DEFAULT;

  if (!fs.existsSync(configFilePath)) {
    if (env.OPIK_CONFIG_PATH) {
      throw new Error(`Config file not found at ${configFilePath}`);
    }

    return {};
  }

  try {
    const config = ini.parse(fs.readFileSync(configFilePath, "utf8"));

    if (!config.opik) {
      return {};
    }

    return filterUndefined({
      apiKey: config.opik.api_key,
      apiUrl: config.opik.url_override,
      projectName: config.opik.project_name,
      workspaceName: config.opik.workspace,
    });
  } catch (error) {
    logger.error(`Error loading config file ${configFilePath}: ${error}`);

    return {};
  }
}
