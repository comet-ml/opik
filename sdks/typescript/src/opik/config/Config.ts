export interface OpikConfig {
  apiKey: string;
  host?: string;
  projectName: string;
  useBatching?: boolean;
  workspaceName: string;
}

const DEFAULT_CONFIG: OpikConfig = {
  apiKey: "",
  host: "http://localhost:5173/api",
  projectName: "Default project",
  useBatching: false,
  workspaceName: "default",
};

function loadFromEnv(): Partial<OpikConfig> {
  return {
    apiKey: process.env.OPIK_API_KEY,
    host: process.env.OPIK_HOST,
    projectName: process.env.OPIK_PROJECT_NAME,
    useBatching: process.env.OPIK_USE_BATCHING === "true",
    workspaceName: process.env.OPIK_WORKSPACE,
  };
}

export function loadConfig(explicit?: Partial<OpikConfig>): OpikConfig {
  const envConfig = loadFromEnv();

  return {
    ...DEFAULT_CONFIG,
    ...envConfig,
    ...explicit,
  };
}
