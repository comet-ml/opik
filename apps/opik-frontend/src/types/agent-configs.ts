export type BlueprintValueType = "string" | "int" | "float" | "boolean" | "Prompt";

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

export enum BlueprintType {
  BLUEPRINT = "blueprint",
  MASK = "mask",
}

export interface Blueprint {
  id: string;
  description?: string;
  type: BlueprintType;
  values: BlueprintValue[];
}

export interface AgentConfig {
  id?: string;
  project_id?: string;
  project_name?: string;
  blueprint: Blueprint;
}

export interface AgentConfigEnv {
  env_name: string;
  blueprint_id: string;
}

export interface AgentConfigEnvsRequest {
  project_id: string;
  envs: AgentConfigEnv[];
}

export interface ConfigHistoryItem {
  id: string;
  description: string;
  created_by: string;
  created_at: string;
  tags: string[];
}

export interface BlueprintDetails {
  id: string;
  description: string;
  created_by: string;
  created_at: string;
  values: BlueprintValue[];
}

// ALEX RENAME