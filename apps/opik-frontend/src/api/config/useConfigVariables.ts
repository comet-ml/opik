import { useQuery } from "@tanstack/react-query";

const CONFIG_BACKEND_URL = "http://localhost:5050";

export type PromptValue = {
  prompt: string;
  prompt_name: string;
};

type ConfigValueType = string | number | boolean | PromptValue;

type ConfigValue = {
  key: string;
  value: ConfigValueType;
  version: number;
  timestamp: string;
  metadata: {
    fallback?: ConfigValueType;
    [key: string]: unknown;
  };
};

export type ConfigVariable = {
  id: string;
  key: string;
  currentValue: ConfigValueType;
  fallback: ConfigValueType;
  type: "string" | "number" | "boolean" | "prompt";
  lastUsed: string;
  version: number;
  experimentCount: number;
};

export type ExperimentOverride = {
  key: string;
  value: ConfigValueType;
  type: "string" | "number" | "boolean" | "prompt";
};

export type ConfigExperiment = {
  id: string;
  overrides: ExperimentOverride[];
  createdAt: string;
};

export type ConfigData = {
  variables: ConfigVariable[];
  experiments: ConfigExperiment[];
};

const isPromptValue = (value: unknown): value is PromptValue => {
  return (
    typeof value === "object" &&
    value !== null &&
    "prompt" in value &&
    "prompt_name" in value
  );
};

const inferType = (
  value: unknown,
  key: string,
): "string" | "number" | "boolean" | "prompt" => {
  if (typeof value === "boolean") return "boolean";
  if (typeof value === "number") return "number";
  if (isPromptValue(value)) return "prompt";
  if (key.includes("prompt")) return "prompt";
  return "string";
};

const fetchConfigData = async (): Promise<ConfigData> => {
  const response = await fetch(`${CONFIG_BACKEND_URL}/config/list`);
  if (!response.ok) {
    throw new Error("Failed to fetch config");
  }

  const data: Record<string, ConfigValue> = await response.json();

  // Separate base keys from experiment-specific keys
  const baseKeys = Object.keys(data).filter((key) => !key.includes(":"));
  const experimentKeys = Object.keys(data).filter((key) => key.includes(":"));

  // Group experiment keys by experiment ID
  const experimentMap = new Map<
    string,
    { overrides: ExperimentOverride[]; createdAt: string }
  >();

  // Count experiments per variable
  const experimentCountMap = new Map<string, Set<string>>();

  for (const fullKey of experimentKeys) {
    const colonIndex = fullKey.lastIndexOf(":");
    const variableKey = fullKey.substring(0, colonIndex);
    const experimentId = fullKey.substring(colonIndex + 1);
    const item = data[fullKey];

    // Track experiment count per variable
    if (!experimentCountMap.has(variableKey)) {
      experimentCountMap.set(variableKey, new Set());
    }
    experimentCountMap.get(variableKey)!.add(experimentId);

    // Build experiment data
    if (!experimentMap.has(experimentId)) {
      experimentMap.set(experimentId, { overrides: [], createdAt: item.timestamp });
    }
    const experiment = experimentMap.get(experimentId)!;
    experiment.overrides.push({
      key: variableKey,
      value: item.value,
      type: inferType(item.value, variableKey),
    });
    // Use earliest timestamp as createdAt
    if (item.timestamp < experiment.createdAt) {
      experiment.createdAt = item.timestamp;
    }
  }

  const variables: ConfigVariable[] = baseKeys.map((key) => {
    const item = data[key];
    return {
      id: key,
      key: key,
      currentValue: item.value,
      fallback: item.metadata.fallback ?? item.value,
      type: inferType(item.value, key),
      lastUsed: item.timestamp,
      version: item.version,
      experimentCount: experimentCountMap.get(key)?.size ?? 0,
    };
  });

  const experiments: ConfigExperiment[] = Array.from(experimentMap.entries())
    .map(([id, data]) => ({
      id,
      overrides: data.overrides,
      createdAt: data.createdAt,
    }))
    .sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime());

  return { variables, experiments };
};

const useConfigVariables = () => {
  return useQuery({
    queryKey: ["config-variables"],
    queryFn: fetchConfigData,
    refetchInterval: 2000,
  });
};

export default useConfigVariables;
