import { Cell } from "@tanstack/react-table";

export type Updater<T> = T | ((old: T) => T);
export type OnChangeFn<T> = (updaterOrValue: Updater<T>) => void;

export type DropdownOption<TDataType> = {
  value: TDataType;
  label: string;
  description?: string;
};

export const COLUMN_ID_ID = "id";
export const COLUMN_SELECT_ID = "select";
export const COLUMN_NAME_ID = "name";
export const COLUMN_ACTIONS_ID = "actions";

export enum COLUMN_TYPE {
  string = "string",
  number = "number",
  list = "list",
  time = "date_time",
  dictionary = "dictionary",
  numberDictionary = "feedback_scores_number",
}

export enum DYNAMIC_COLUMN_TYPE {
  string = "string",
  number = "number",
  object = "object",
  array = "array",
  boolean = "boolean",
  null = "null",
}

export type ColumnData<T> = {
  id: string;
  label: string;
  disabled?: boolean;
  accessorFn?: (row: T) => string;
  size?: number;
  type?: COLUMN_TYPE;
  customMeta?: object;
  iconType?: COLUMN_TYPE;
  cell?: Cell<T, unknown>;
  verticalAlignment?: CELL_VERTICAL_ALIGNMENT;
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
