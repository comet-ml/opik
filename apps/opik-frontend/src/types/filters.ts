import { COLUMN_TYPE, DropdownOption } from "@/types/shared";
import React from "react";

export type FilterOperator =
  | "contains"
  | "not_contains"
  | "starts_with"
  | "ends_with"
  | "is_empty"
  | "is_not_empty"
  | "not_in"
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

export type FilterKeySelectorComponentProps = {
  className?: string;
  placeholder?: string;
  value: string;
  onValueChange: (value: string) => void;
  disabled?: boolean;
  "data-testid"?: string;
};

export type FilterRowConfig = {
  keyComponent?: React.FC<unknown> & {
    placeholder: string;
    value: string;
    onValueChange: (value: string) => void;
  };
  keyComponentProps?: unknown;
  keySelectorComponent?: React.ComponentType<FilterKeySelectorComponentProps>;
  keySelectorComponentProps?: Partial<FilterKeySelectorComponentProps>;
  defaultOperator?: FilterOperator;
  operators?: DropdownOption<FilterOperator>[];
  validateFilter?: (filter: Filter) => string | undefined;
};

export type Filters = Filter[];
