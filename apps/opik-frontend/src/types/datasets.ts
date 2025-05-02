import { TraceFeedbackScore } from "@/types/traces";
import { AggregatedFeedbackScore, DYNAMIC_COLUMN_TYPE } from "@/types/shared";
import { CommentItems } from "./comment";

export interface Dataset {
  id: string;
  name: string;
  description?: string;
  dataset_items_count: number;
  experiment_count: number;
  most_recent_experiment_at: string;
  last_created_experiment_at: string;
  most_recent_optimization_at: string;
  last_created_optimization_at: string;
  optimization_count: number;
  created_at: string;
  last_updated_at: string;
}

export enum DATASET_ITEM_SOURCE {
  manual = "manual",
  trace = "trace",
  span = "span",
  sdk = "sdk",
}

export interface DatasetItem {
  id: string;
  data: object;
  source: DATASET_ITEM_SOURCE;
  trace_id?: string;
  span_id?: string;
  created_at: string;
  last_updated_at: string;
}

export interface DatasetItemColumn {
  name: string;
  types: DYNAMIC_COLUMN_TYPE[];
}

export interface ExperimentOutputColumn {
  id: string;
  name: string;
  types: DYNAMIC_COLUMN_TYPE[];
}

export interface ExperimentPromptVersion {
  id: string;
  commit: string;
  prompt_id: string;
}

export enum EXPERIMENT_TYPE {
  REGULAR = "regular",
  TRIAL = "trial",
  MINI_BATCH = "mini-batch",
}

export interface Experiment {
  id: string;
  dataset_id: string;
  dataset_name: string;
  optimization_id?: string;
  type: EXPERIMENT_TYPE;
  status: string;
  metadata?: object;
  name: string;
  feedback_scores?: AggregatedFeedbackScore[];
  // @deprecated
  prompt_version?: ExperimentPromptVersion;
  prompt_versions?: ExperimentPromptVersion[];
  trace_count: number;
  created_at: string;
  last_updated_at: string;
  comments?: CommentItems;
}

export interface ExperimentItem {
  id: string;
  experiment_id: string;
  dataset_item_id: string;
  trace_id?: string;
  input: object;
  output: object;
  feedback_scores?: TraceFeedbackScore[];
  comments?: CommentItems;
  created_at: string;
  last_updated_at: string;
}

export interface ExperimentsCompare extends DatasetItem {
  experiment_items: ExperimentItem[];
}
