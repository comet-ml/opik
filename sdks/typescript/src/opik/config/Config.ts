export interface OpikConfig {
  apiKey: string;
  apiUrl?: string;
  projectName: string;
  workspaceName: string;
}

// ALEX
const DEFAULT_CONFIG: OpikConfig = {
  apiKey: "",
  apiUrl: "http://localhost:5173/api",
  projectName: "Default Project",
  workspaceName: "default",
};

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
