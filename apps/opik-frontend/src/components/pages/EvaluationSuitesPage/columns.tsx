import { DATASET_TYPE } from "@/types/datasets";
import { COLUMN_NAME_ID } from "@/types/shared";

export const TYPE_LABELS: Record<string, string> = {
  [DATASET_TYPE.EVALUATION_SUITE]: "Evaluation suite",
  [DATASET_TYPE.DATASET]: "Dataset",
};

export const DEFAULT_SELECTED_COLUMNS: string[] = [
  COLUMN_NAME_ID,
  "type",
  "description",
  "dataset_items_count",
  "most_recent_experiment_at",
  "last_updated_at",
];
