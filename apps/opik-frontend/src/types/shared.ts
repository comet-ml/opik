import { Cell, Header } from "@tanstack/react-table";

export type Updater<T> = T | ((old: T) => T);
export type OnChangeFn<T> = (updaterOrValue: Updater<T>) => void;

export type DropdownOption<TDataType> = {
  value: TDataType;
  label: string;
  description?: string;
  tooltip?: string;
  disabled?: boolean;
};

export const COLUMN_ID_ID = "id";
export const COLUMN_SELECT_ID = "select";
export const COLUMN_NAME_ID = "name";
export const COLUMN_ACTIONS_ID = "actions";
export const COLUMN_METADATA_ID = "metadata";
export const COLUMN_FEEDBACK_SCORES_ID = "feedback_scores";
export const COLUMN_COMMENTS_ID = "comments";
export const COLUMN_GUARDRAILS_ID = "guardrails";
export const COLUMN_CREATED_AT_ID = "created_at";

export const COLUMN_GUARDRAIL_STATISTIC_ID = "guardrails_failed_count";

export enum COLUMN_TYPE {
  string = "string",
  number = "number",
  list = "list",
  time = "date_time",
  duration = "duration",
  dictionary = "dictionary",
  numberDictionary = "feedback_scores_number",
  cost = "cost",
  guardrails = "guardrails",
}

export enum DYNAMIC_COLUMN_TYPE {
  string = "string",
  number = "number",
  object = "object",
  array = "array",
  boolean = "boolean",
  null = "null",
}

export type HeaderIconType = COLUMN_TYPE;

export type ColumnData<T> = {
  id: string;
  label: string;
  disabled?: boolean;
  accessorFn?: (row: T) => string | number | object | undefined;
  size?: number;
  type?: COLUMN_TYPE;
  customMeta?: object;
  iconType?: HeaderIconType;
  header?: Header<T, unknown>;
  headerCheckbox?: boolean;
  cell?: Cell<T, unknown>;
  verticalAlignment?: CELL_VERTICAL_ALIGNMENT;
  overrideRowHeight?: ROW_HEIGHT;
  statisticKey?: string;
  statisticDataFormater?: (value: number) => string;
  sortable?: boolean;
};

export type DynamicColumn = {
  id: string;
  label: string;
  columnType: COLUMN_TYPE;
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
