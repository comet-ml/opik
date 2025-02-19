import fs from "fs";
import ini from "ini";

export interface OpikConfig {
  apiKey: string;
  apiUrl?: string;
  projectName: string;
  workspaceName: string;
}

const CONFIG_FILE_PATH_DEFAULT = "~/.opik.config";
const CONFIG_FILE_PATH =
  process.env.OPIK_CONFIG_PATH || CONFIG_FILE_PATH_DEFAULT;

const DEFAULT_CONFIG: OpikConfig = {
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
  const config = ini.parse(fs.readFileSync(CONFIG_FILE_PATH, "utf8"));

  if (!config.opik) {
    return {};
  }

  return filterUndefined({
    apiKey: config.opik.api_key,
    apiUrl: config.opik.url_override,
    projectName: config.opik.project_name,
    workspaceName: config.opik.workspace,
  });
}

export function loadConfig(explicit?: Partial<OpikConfig>): OpikConfig {
  const envConfig = loadFromEnv();
  const fileConfig = loadFromConfigFile();

  return validateConfig({
    ...DEFAULT_CONFIG,
    ...fileConfig,
    ...envConfig,
    ...explicit,
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

function isCloud(host: string) {
  return new URL(host).hostname.endsWith("comet.com");
}
