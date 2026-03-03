export type BlueprintValueType = "string" | "number" | "boolean" | "Prompt";

export interface BlueprintValue {
  key: string;
  type: BlueprintValueType;
  value: string;
  description?: string;
}

export interface EnrichedBlueprintValue extends BlueprintValue {
  promptName?: string;
  promptId?: string;
}

enum BlueprintType {
  BLUEPRINT = "blueprint",
  MASK = "mask",
}

export interface Blueprint {
  id: string;
  description?: string;
  type: BlueprintType;
  values: BlueprintValue[];
}

export interface OptimizerConfig {
  id?: string;
  projectId?: string;
  // ALEX: Check if we need projectName
  blueprint: Blueprint;
}

export interface OptimizerConfigEnv {
  env_name: string;
  blueprint_id: string;
}

export interface OptimizerConfigEnvsRequest {
  projectId: string;
  envs: OptimizerConfigEnv[];
}

export interface ConfigHistoryItem {
  id: string;
  description: string;
  createdBy: string;
  createdAt: string;
  tags: string[];
}

export interface BlueprintDetails {
  id: string;
  description: string;
  createdBy: string;
  createdAt: string;
  values: BlueprintValue[];
}
