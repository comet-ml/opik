export interface OpikConfig {
  apiKey: string;
  host?: string;
  projectName: string;
  workspaceName: string;
}

const DEFAULT_CONFIG: OpikConfig = {
  apiKey: "",
  host: "http://localhost:5173/api",
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
    host: process.env.OPIK_HOST,
    projectName: process.env.OPIK_PROJECT_NAME,
    workspaceName: process.env.OPIK_WORKSPACE,
  });
}

export function loadConfig(explicit?: Partial<OpikConfig>): OpikConfig {
  const envConfig = loadFromEnv();

  return validateConfig({
    ...DEFAULT_CONFIG,
    ...envConfig,
    ...explicit,
  });
}

export function validateConfig(config: OpikConfig) {
  if (!config.host) {
    throw new Error("OPIK_HOST is not set");
  }

  const isCloudHost = isCloud(config.host);

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
