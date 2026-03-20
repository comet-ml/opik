export enum BlueprintValueType {
  STRING = "string",
  INT = "integer",
  FLOAT = "float",
  BOOLEAN = "boolean",
  PROMPT = "prompt",
}

export interface BlueprintValue {
  key: string;
  type: BlueprintValueType;
  value: string;
  description?: string;
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

export type BlueprintCreate = Omit<Blueprint, "id">;

export interface AgentConfig {
  id?: string;
  project_id?: string;
  project_name?: string;
  blueprint: Blueprint;
}

export type AgentConfigCreate = Omit<AgentConfig, "id" | "blueprint"> & {
  blueprint: BlueprintCreate;
};

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
  name: string;
  description: string;
  created_by: string;
  created_at: string;
  tags: string[];
  values: BlueprintValue[];
}

export interface BlueprintDetails {
  id: string;
  description: string;
  created_by: string;
  created_at: string;
  values: BlueprintValue[];
}
