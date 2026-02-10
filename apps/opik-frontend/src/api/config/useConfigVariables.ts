import { useQuery } from "@tanstack/react-query";

const CONFIG_BACKEND_URL = "http://localhost:5050";

export type PromptValue = {
  prompt: string;
  prompt_name: string;
};

type ConfigValueType = string | number | boolean | PromptValue;

export type ConfigVariable = {
  id: string;
  key: string;
  currentValue: ConfigValueType;
  fallback: ConfigValueType;
  type: "string" | "number" | "boolean" | "prompt";
  lastUsed: string;
  version: number;
  experimentCount: number;
  annotations?: string[];
};

export type ExperimentOverride = {
  key: string;
  value: ConfigValueType;
  type: "string" | "number" | "boolean" | "prompt";
};

export type ConfigExperiment = {
  id: string;
  name: string;
  overrides: ExperimentOverride[];
  createdAt: string;
  isAb?: boolean;
  distribution?: Record<string, number>;
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

type PublishedValue = {
  key: string;
  value: unknown;
  value_id: number;
  updated_at: string;
  original_value?: unknown;
  version?: number;
  annotations?: string[];
};

type Mask = {
  mask_id: string;
  name: string;
  is_ab: boolean;
  salt: string;
  distribution: Record<string, number> | null;
  created_at: string;
  updated_at: string;
};

type MaskOverride = {
  variant: string;
  key: string;
  value: unknown;
  value_id: number;
  updated_at: string;
};

const fetchConfigData = async (projectId: string): Promise<ConfigData> => {
  const [publishedRes, masksRes] = await Promise.all([
    fetch(`${CONFIG_BACKEND_URL}/v1/config/published?project_id=${projectId}&env=prod`),
    fetch(`${CONFIG_BACKEND_URL}/v1/config/masks?project_id=${projectId}&env=prod`),
  ]);

  if (!publishedRes.ok || !masksRes.ok) {
    throw new Error("Failed to fetch config");
  }

  const { values: published }: { values: PublishedValue[] } = await publishedRes.json();
  const { masks }: { masks: Mask[] } = await masksRes.json();

  // Fetch overrides for each mask
  const overridesPromises = masks.map(async (mask) => {
    const res = await fetch(
      `${CONFIG_BACKEND_URL}/v1/config/masks/${mask.mask_id}/overrides?project_id=${projectId}&env=prod`
    );
    if (!res.ok) return { mask_id: mask.mask_id, overrides: [] };
    const { overrides }: { overrides: MaskOverride[] } = await res.json();
    return { mask_id: mask.mask_id, overrides };
  });
  const maskOverrides = await Promise.all(overridesPromises);
  const overridesMap = new Map(maskOverrides.map((m) => [m.mask_id, m.overrides]));

  // Count experiments per variable
  const experimentCountMap = new Map<string, Set<string>>();
  for (const mask of masks) {
    const overrides = overridesMap.get(mask.mask_id) || [];
    for (const override of overrides) {
      if (!experimentCountMap.has(override.key)) {
        experimentCountMap.set(override.key, new Set());
      }
      experimentCountMap.get(override.key)!.add(mask.mask_id);
    }
  }

  const variables: ConfigVariable[] = published.map((item) => ({
    id: item.key,
    key: item.key,
    currentValue: item.value as ConfigValueType,
    fallback: (item.original_value ?? item.value) as ConfigValueType,
    type: inferType(item.value, item.key),
    lastUsed: item.updated_at,
    version: item.version ?? 1,
    experimentCount: experimentCountMap.get(item.key)?.size ?? 0,
    annotations: item.annotations,
  }));

  const experiments: ConfigExperiment[] = masks.map((mask) => {
    const overrides = overridesMap.get(mask.mask_id) || [];
    // Group by variant and use default variant for non-A/B tests
    const uniqueOverrides = new Map<string, ExperimentOverride>();
    for (const override of overrides) {
      if (!uniqueOverrides.has(override.key)) {
        uniqueOverrides.set(override.key, {
          key: override.key,
          value: override.value as ConfigValueType,
          type: inferType(override.value, override.key),
        });
      }
    }
    return {
      id: mask.mask_id,
      name: mask.name,
      overrides: Array.from(uniqueOverrides.values()),
      createdAt: mask.created_at,
      isAb: mask.is_ab,
      distribution: mask.distribution ?? undefined,
    };
  }).sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime());

  return { variables, experiments };
};

type UseConfigVariablesParams = {
  projectId?: string;
};

const useConfigVariables = (params: UseConfigVariablesParams = {}) => {
  const { projectId = "default" } = params;

  return useQuery({
    queryKey: ["config-variables", projectId],
    queryFn: () => fetchConfigData(projectId),
    refetchInterval: 2000,
  });
};

export default useConfigVariables;
