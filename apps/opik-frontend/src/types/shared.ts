import { Cell, Header } from "@tanstack/react-table";

export type Updater<T> = T | ((old: T) => T);
export type OnChangeFn<T> = (updaterOrValue: Updater<T>) => void;

export type DropdownOption<TDataType> = {
  value: TDataType;
  label: string;
  description?: string;
  tooltip?: string;
  disabled?: boolean;
  action?: {
    href?: string;
  };
};

export const COLUMN_ID_ID = "id";
export const COLUMN_SELECT_ID = "select";
export const COLUMN_NAME_ID = "name";
export const COLUMN_ACTIONS_ID = "actions";
export const COLUMN_METADATA_ID = "metadata";
export const COLUMN_FEEDBACK_SCORES_ID = "feedback_scores";
export const COLUMN_EXPERIMENT_SCORES_ID = "experiment_scores";
export const COLUMN_SPAN_FEEDBACK_SCORES_ID = "span_feedback_scores";
export const COLUMN_USAGE_ID = "usage";

// Score type constants
export const SCORE_TYPE_FEEDBACK = "feedback_scores" as const;
export const SCORE_TYPE_EXPERIMENT = "experiment_scores" as const;
export type ScoreType =
  | typeof SCORE_TYPE_FEEDBACK
  | typeof SCORE_TYPE_EXPERIMENT;
export const COLUMN_COMMENTS_ID = "comments";
export const COLUMN_GUARDRAILS_ID = "guardrails";
export const COLUMN_CREATED_AT_ID = "created_at";
export const COLUMN_DATASET_ID = "dataset_id";
export const COLUMN_PROJECT_ID = "project_id";
export const COLUMN_DURATION_ID = "duration";
export const COLUMN_CUSTOM_ID = "custom";

export const COLUMN_GUARDRAIL_STATISTIC_ID = "guardrails_failed_count";
export const COLUMN_DATA_ID = "data";

export enum COLUMN_TYPE {
  string = "string",
  number = "number",
  list = "list",
  time = "date_time",
  duration = "duration",
  dictionary = "dictionary",
  numberDictionary = "feedback_scores_number",
  cost = "cost",
  category = "category",
  errors = "errors",
}

export enum DYNAMIC_COLUMN_TYPE {
  string = "string",
  number = "number",
  object = "object",
  array = "array",
  boolean = "boolean",
  null = "null",
}

type explainerType = "info" | "help";

export type Explainer = {
  id: string;
  title?: string;
  type?: explainerType;
  description: string;
  docLink?: string;
  docHash?: string;
};

export type HeaderIconType = COLUMN_TYPE | "guardrails" | "tags" | "version";

export type ColumnData<T> = {
  id: string;
  label: string;
  disabled?: boolean;
  accessorFn?: (row: T) => string | number | object | boolean | undefined;
  size?: number;
  minSize?: number;
  type?: COLUMN_TYPE;
  scoreType?: ScoreType;
  customMeta?: object;
  iconType?: HeaderIconType;
  header?: Header<T, unknown>;
  headerCheckbox?: boolean;
  explainer?: Explainer;
  cell?: Cell<T, unknown>;
  aggregatedCell?: Cell<T, unknown>;
  verticalAlignment?: CELL_VERTICAL_ALIGNMENT;
  overrideRowHeight?: ROW_HEIGHT;
  statisticKey?: string;
  statisticDataFormater?: (value: number) => string;
  supportsPercentiles?: boolean;
  sortable?: boolean;
  disposable?: boolean;
};

export type DynamicColumn = {
  id: string;
  label: string;
  columnType: COLUMN_TYPE;
  type?: ScoreType;
};

export enum ROW_HEIGHT {
  small = "small",
  medium = "medium",
  large = "large",
}

export enum CELL_VERTICAL_ALIGNMENT {
  start = "start",
  center = "center",
  end = "end",
}

export interface FeedbackScoreName {
  name: string;
  type?: ScoreType;
}

export enum STATISTIC_AGGREGATION_TYPE {
  PERCENTAGE = "PERCENTAGE",
  COUNT = "COUNT",
  AVG = "AVG",
}

export interface PercentageStatisticData {
  value: {
    p50: number;
    p90: number;
    p99: number;
  };
  type: STATISTIC_AGGREGATION_TYPE.PERCENTAGE;
}

export interface CountStatisticData {
  value: number;
  type: STATISTIC_AGGREGATION_TYPE.COUNT;
}

export interface AverageStatisticData {
  value: number;
  type: STATISTIC_AGGREGATION_TYPE.AVG;
}

export type ColumnStatistic = {
  name: string;
} & (PercentageStatisticData | AverageStatisticData | CountStatisticData);

export type ColumnsStatistic = ColumnStatistic[];

export type JsonNode =
  | string
  | number
  | boolean
  | null
  | object
  | JsonNode[]
  | { [key: string]: JsonNode };

export type UsageType = {
  [key: string]: number | UsageType;
};

export interface UsageData {
  prompt_tokens: number;
  completion_tokens: number;
  total_tokens: number;
}

export interface AggregatedFeedbackScore {
  name: string;
  value: number;
}

export interface AggregatedDuration {
  p50: number;
  p90: number;
  p99: number;
}
