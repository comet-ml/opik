import { ProviderMessageType } from "@/types/llm";

export enum OPTIMIZATION_ALGORITHM {
  EVOLUTIONARY = "evolutionary",
  HIERARCHICAL_REFLECTIVE = "hierarchical_reflective",
}

export enum OPTIMIZATION_STUDIO_RUN_STATUS {
  RUNNING = "running",
  COMPLETED = "completed",
  FAILED = "failed",
  CANCELLED = "cancelled",
}

export interface OptimizationStudioRun {
  id: string;
  name: string;
  dataset_id: string;
  dataset_name: string;
  optimization_id?: string;
  prompt: ProviderMessageType[];
  algorithm: OPTIMIZATION_ALGORITHM;
  metric: string;
  status: OPTIMIZATION_STUDIO_RUN_STATUS;
  created_at: string;
  created_by: string;
  last_updated_at: string;
  last_updated_by: string;
}

export type OptimizationStudioRunCreate = Pick<
  OptimizationStudioRun,
  "name" | "dataset_id" | "dataset_name" | "prompt" | "algorithm" | "metric"
>;
