import { COLUMN_TYPE } from "@/types/shared";

export type FilterOperator =
  | "contains"
  | "not_contains"
  | "starts_with"
  | "ends_with"
  | "="
  | ">"
  | ">="
  | "<"
  | "<=";

export interface Filter {
  id: string;
  field: string;
  type: COLUMN_TYPE | "";
  operator: FilterOperator | "";
  key?: string;
  value: string | number;
}

export type Filters = Filter[];
