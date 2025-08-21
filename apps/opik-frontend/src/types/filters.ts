import { COLUMN_TYPE, DropdownOption } from "@/types/shared";
import React from "react";

export type FilterOperator =
  | "contains"
  | "not_contains"
  | "starts_with"
  | "ends_with"
  | "is_empty"
  | "is_not_empty"
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
  error?: string;
}
export type FilterRowConfig = {
  keyComponent?: React.FC<unknown> & {
    placeholder: string;
    value: string;
    onValueChange: (value: string) => void;
  };
  keyComponentProps?: unknown;
  defaultOperator?: FilterOperator;
  operators?: DropdownOption<FilterOperator>[];
  validateFilter?: (filter: Filter) => string | undefined;
};

export type Filters = Filter[];
