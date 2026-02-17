import { TRACE_VISIBILITY_MODE, TraceFeedbackScore } from "@/types/traces";
import {
  AggregatedDuration,
  AggregatedFeedbackScore,
  DYNAMIC_COLUMN_TYPE,
  UsageData,
} from "@/types/shared";
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
  tags?: string[];
  created_at: string;
  last_updated_at: string;
  status?: DATASET_STATUS;
  latest_version?: Pick<
    DatasetVersion,
    "id" | "version_hash" | "version_name" | "tags" | "change_description"
  >;
}

export interface DatasetVersion {
  id: string;
  dataset_id: string;
  version_hash: string;
  version_name?: string;
  items_total: number;
  items_added: number;
  items_modified: number;
  items_deleted: number;
  change_description?: string;
  metadata?: Record<string, unknown>;
  tags?: string[];
  created_at: string;
  created_by: string;
  last_updated_at: string;
  last_updated_by: string;
}

export enum DATASET_STATUS {
  unknown = "unknown",
  processing = "processing",
  completed = "completed",
  failed = "failed",
}

export enum DATASET_ITEM_SOURCE {
  manual = "manual",
  trace = "trace",
  span = "span",
  sdk = "sdk",
}

export enum DATASET_ITEM_DRAFT_STATUS {
  unchanged = "unchanged",
  added = "added",
  edited = "edited",
  // deleted items are filtered out, not shown
}

export interface DatasetItem {
  id: string;
  dataset_item_id?: string;
  data: object;
  source: DATASET_ITEM_SOURCE;
  trace_id?: string;
  span_id?: string;
  tags?: string[];
  created_at: string;
  last_updated_at: string;
}

export interface DatasetItemWithDraft extends DatasetItem {
  draftStatus?: DATASET_ITEM_DRAFT_STATUS;
  tempId?: string;
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
  prompt_name: string;
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
  dataset_version_summary?: Pick<
    DatasetVersion,
    "id" | "version_hash" | "version_name" | "tags" | "change_description"
  >;
  project_id?: string;
  project_name?: string;
  optimization_id?: string;
  type: EXPERIMENT_TYPE;
  status: string;
  metadata?: object;
  name: string;
  tags?: string[];
  feedback_scores?: AggregatedFeedbackScore[];
  experiment_scores?: AggregatedFeedbackScore[];
  duration?: AggregatedDuration;
  // @deprecated
  prompt_version?: ExperimentPromptVersion;
  prompt_versions?: ExperimentPromptVersion[];
  trace_count: number;
  total_estimated_cost?: number;
  total_estimated_cost_avg?: number;
  created_at: string;
  last_updated_at: string;
  comments?: CommentItems;
}

export interface ExperimentItem {
  id: string;
  experiment_id: string;
  dataset_item_id: string;
  trace_id?: string;
  trace_visibility_mode?: TRACE_VISIBILITY_MODE;
  input: object;
  output: object;
  feedback_scores?: TraceFeedbackScore[];
  duration?: number;
  usage?: UsageData;
  total_estimated_cost?: number;
  comments?: CommentItems;
  created_at: string;
  last_updated_at: string;
}

export interface ExperimentsCompare extends DatasetItem {
  experiment_items: ExperimentItem[];
}

export interface ExperimentsAggregations {
  experiment_count: number;
  trace_count: number;
  total_estimated_cost?: number;
  total_estimated_cost_avg?: number;
  duration?: AggregatedDuration;
  feedback_scores?: AggregatedFeedbackScore[];
  experiment_scores?: AggregatedFeedbackScore[];
}

export interface ExperimentsGroupNode {
  label?: string;
  groups?: Record<string, ExperimentsGroupNode>;
}

export interface ExperimentsGroupNodeWithAggregations {
  label?: string;
  aggregations?: ExperimentsAggregations;
  groups?: Record<string, ExperimentsGroupNodeWithAggregations>;
}

export interface DatasetExpansionRequest {
  model: string;
  sample_count: number;
  preserve_fields?: string[];
  variation_instructions?: string;
  custom_prompt?: string;
}

export interface DatasetExpansionResponse {
  generated_samples: DatasetItem[];
  model: string;
  total_generated: number;
  generation_time: string;
}

export enum DATASET_EXPORT_STATUS {
  PENDING = "PENDING",
  PROCESSING = "PROCESSING",
  COMPLETED = "COMPLETED",
  FAILED = "FAILED",
}

export interface DatasetExportJob {
  id: string;
  dataset_id: string;
  dataset_name?: string;
  status: DATASET_EXPORT_STATUS;
  file_path?: string;
  download_url?: string;
  error_message?: string;
  created_at: string;
  last_updated_at: string;
  expires_at?: string;
  viewed_at?: string;
  created_by?: string;
  last_updated_by?: string;
}
