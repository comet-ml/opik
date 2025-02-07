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

  return {
    ...DEFAULT_CONFIG,
    ...envConfig,
    ...explicit,
  };
}
