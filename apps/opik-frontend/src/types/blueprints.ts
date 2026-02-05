export interface Blueprint {
  id: string;
  project_id: string;
  name: string;
  created_at: string;
}

export type DeploymentChangeType = "optimizer" | "manual" | "rollback";

export interface DeploymentVersion {
  id: string;
  blueprint_id: string;
  version_number: number;
  snapshot: DeploymentSnapshot;
  change_summary: string | null;
  change_type: DeploymentChangeType;
  source_experiment_id: string | null;
  created_at: string;
  created_by: string | null;
}

export interface DeploymentSnapshot {
  prompts: Record<string, PromptConfig>;
  config: Record<string, unknown>;
}

export interface PromptConfig {
  prompt_name: string;
  prompt: string;
}

export interface BlueprintHistory {
  versions: DeploymentVersion[];
  latest_version: number;
  pointers: Record<string, number>; // e.g., {"latest": 5, "prod": 3, "stage": 4}
}

export interface EnvironmentPointer {
  blueprint_id: string;
  env: string; // "latest", "prod", "stage", etc.
  version_number: number;
  updated_at: string;
}

export interface CommitResultWithDeployment {
  prompt_name: string;
  commit: string;
  opik_prompt_id: string;
  opik_version_id: string;
  deployment_version?: number;
  blueprint_id?: string;
}
