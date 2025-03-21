import { logger } from "@/utils/logger";

export interface OpikConfig {
  apiKey: string;
  apiUrl?: string;
  projectName: string;
  workspaceName: string;
}

const CONFIG_FILE_PATH_DEFAULT = "~/.opik.config";

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

export function loadConfig(explicit?: Partial<OpikConfig>): OpikConfig {

  return validateConfig({
    ...DEFAULT_CONFIG,
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

function isCloud(apiUrl: string) {
  return new URL(apiUrl).hostname.endsWith("comet.com");
}
