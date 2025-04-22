import { FilterOperator } from "@/types/filters";
import { COLUMN_TYPE, DropdownOption } from "@/types/shared";

export const DEFAULT_OPERATORS: DropdownOption<FilterOperator>[] = [
  { label: "contains", value: "contains" },
];

export const DEFAULT_OPERATOR_MAP: Record<COLUMN_TYPE, FilterOperator> = {
  [COLUMN_TYPE.string]: "contains",
  [COLUMN_TYPE.number]: ">=",
  [COLUMN_TYPE.list]: "contains",
  [COLUMN_TYPE.time]: ">=",
  [COLUMN_TYPE.dictionary]: "=",
  [COLUMN_TYPE.numberDictionary]: "=",
  [COLUMN_TYPE.cost]: "<=",
  [COLUMN_TYPE.duration]: "<=",
  [COLUMN_TYPE.guardrails]: "=",
};

export const OPERATORS_MAP: Record<
  COLUMN_TYPE,
  DropdownOption<FilterOperator>[]
> = {
  [COLUMN_TYPE.string]: [
    {
      label: "=",
      value: "=",
    },
    {
      label: "contains",
      value: "contains",
    },
    {
      label: "doesn't contain",
      value: "not_contains",
    },
    {
      label: "starts with",
      value: "starts_with",
    },
    {
      label: "ends with",
      value: "ends_with",
    },
  ],
  [COLUMN_TYPE.number]: [
    {
      label: "=",
      value: "=",
    },
    {
      label: ">",
      value: ">",
    },
    {
      label: ">=",
      value: ">=",
    },
    {
      label: "<",
      value: "<",
    },
    {
      label: "<=",
      value: "<=",
    },
  ],
  [COLUMN_TYPE.cost]: [
    {
      label: "=",
      value: "=",
    },
    {
      label: ">",
      value: ">",
    },
    {
      label: ">=",
      value: ">=",
    },
    {
      label: "<",
      value: "<",
    },
    {
      label: "<=",
      value: "<=",
    },
  ],
  [COLUMN_TYPE.duration]: [
    {
      label: "=",
      value: "=",
    },
    {
      label: ">",
      value: ">",
    },
    {
      label: ">=",
      value: ">=",
    },
    {
      label: "<",
      value: "<",
    },
    {
      label: "<=",
      value: "<=",
    },
  ],
  [COLUMN_TYPE.list]: [
    {
      label: "contains",
      value: "contains",
    },
  ],
  [COLUMN_TYPE.time]: [
    {
      label: "=",
      value: "=",
    },
    {
      label: ">",
      value: ">",
    },
    {
      label: ">=",
      value: ">=",
    },
    {
      label: "<",
      value: "<",
    },
    {
      label: "<=",
      value: "<=",
    },
  ],
  [COLUMN_TYPE.dictionary]: [
    {
      label: "=",
      value: "=",
    },
    {
      label: "contains",
      value: "contains",
    },
    {
      label: ">",
      value: ">",
    },
    {
      label: "<",
      value: "<",
    },
  ],
  [COLUMN_TYPE.numberDictionary]: [
    {
      label: "=",
      value: "=",
    },
    {
      label: ">",
      value: ">",
    },
    {
      label: ">=",
      value: ">=",
    },
    {
      label: "<",
      value: "<",
    },
    {
      label: "<=",
      value: "<=",
    },
    {
      label: "is empty",
      value: "is_empty",
    },
    {
      label: "is not empty",
      value: "is_not_empty",
    },
  ],
  [COLUMN_TYPE.guardrails]: [
    {
      label: "=",
      value: "=",
    },
  ],
};
