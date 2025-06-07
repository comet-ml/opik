import { logger } from "@/utils/logger";
import fs from "fs";
import ini from "ini";
import { RequestOptions } from "@/types/request";
import "dotenv/config";

export interface OpikConfig {
  apiKey: string;
  apiUrl?: string;
  projectName: string;
  workspaceName: string;
  requestOptions?: RequestOptions;
}

// ALEX
export interface ConstructorOpikConfig extends OpikConfig {
  headers?: Record<string, string>;
}

const CONFIG_FILE_PATH_DEFAULT = "~/.opik.config";

export const DEFAULT_CONFIG: Required<Omit<OpikConfig, "requestOptions">> = {
  apiKey: "",
  apiUrl: "http://localhost:5173/api",
  projectName: "Default Project",
  workspaceName: "default",
};

function filterUndefined<T extends object>(obj: Partial<T>): Partial<T> {
  return Object.fromEntries(
    Object.entries(obj).filter(([, value]) => value !== undefined)
  ) as Partial<T>;
}

function loadFromEnv(): Partial<OpikConfig> {
  return filterUndefined({
    apiKey: process.env.OPIK_API_KEY,
    apiUrl: process.env.OPIK_URL_OVERRIDE,
    projectName: process.env.OPIK_PROJECT_NAME,
    workspaceName: process.env.OPIK_WORKSPACE,
  });
}

function loadFromConfigFile(): Partial<OpikConfig> {
  const configFilePath =
    process.env.OPIK_CONFIG_PATH || CONFIG_FILE_PATH_DEFAULT;

  if (!fs.existsSync(configFilePath)) {
    if (process.env.OPIK_CONFIG_PATH) {
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

export function loadConfig(
  explicit?: Partial<ConstructorOpikConfig>
): OpikConfig {
  const envConfig = loadFromEnv();
  const fileConfig = loadFromConfigFile();

  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  const { headers: _, ...explicitConfig } = explicit || {};

  return validateConfig({
    ...DEFAULT_CONFIG,
    ...fileConfig,
    ...envConfig,
    ...explicitConfig,
  });
}

export function validateConfig(config: OpikConfig) {
  if (!config.apiUrl) {
    throw new Error("OPIK_URL_OVERRIDE is not set");
  }

  const isCloudHost = isCloud(config.apiUrl);

  if (isCloudHost && !config.apiKey) {
    throw new Error("OPIK_API_KEY is not set");
  }

  if (
    isCloudHost &&
    (!config.workspaceName || config.workspaceName === "default")
  ) {
    throw new Error("OPIK_WORKSPACE is not set");
  }

  return config;
}

function isCloud(apiUrl: string) {
  return new URL(apiUrl).hostname.endsWith("comet.com");
}
