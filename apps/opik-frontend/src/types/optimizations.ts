import { AggregatedFeedbackScore } from "@/types/shared";

export enum OPTIMIZATION_STATUS {
  RUNNING = "running",
  COMPLETED = "completed",
  CANCELLED = "cancelled",
}

export interface Optimization {
  id: string;
  name: string;
  dataset_id: string;
  dataset_name: string;
  metadata?: object;
  feedback_scores?: AggregatedFeedbackScore[];
  num_trials: number;
  objective_name: string;
  status: OPTIMIZATION_STATUS;
  created_at: string;
  created_by: string;
  last_updated_at: string;
  last_updated_by: string;
}
