import { TraceFeedbackScore } from "@/types/traces";
import { DYNAMIC_COLUMN_TYPE } from "@/types/shared";

export interface Dataset {
  id: string;
  name: string;
  description?: string;
  experiment_count: number;
  most_recent_experiment_at: string;
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
  type: DYNAMIC_COLUMN_TYPE;
}

export interface AverageFeedbackScore {
  name: string;
  value: number;
}

export interface Experiment {
  id: string;
  dataset_id: string;
  dataset_name: string;
  metadata?: object;
  name: string;
  feedback_scores?: AverageFeedbackScore[];
  trace_count: number;
  created_at: string;
  last_updated_at: string;
}

export interface ExperimentItem {
  id: string;
  experiment_id: string;
  dataset_item_id: string;
  trace_id?: string;
  input: object;
  output: object;
  feedback_scores?: TraceFeedbackScore[];
  created_at: string;
  last_updated_at: string;
}

export interface ExperimentsCompare extends DatasetItem {
  experiment_items: ExperimentItem[];
}
