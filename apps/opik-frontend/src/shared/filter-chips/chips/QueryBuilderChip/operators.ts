import { FilterOperator } from "@/types/filters";
import { NO_VALUE_OPERATORS } from "@/constants/filters";

const OPERATOR_LABELS: Partial<Record<FilterOperator, string>> = {
  "=": "equals",
  contains: "contains",
  not_contains: "does not contain",
  starts_with: "starts with",
  ends_with: "ends with",
  ">": "more than",
  ">=": "at least",
  "<": "less than",
  "<=": "at most",
  is_empty: "is empty",
  is_not_empty: "is not empty",
};

const OPERATOR_SHORT_LABELS: Partial<Record<FilterOperator, string>> = {
  "=": "=",
  contains: "contains",
  not_contains: "not contains",
  starts_with: "starts",
  ends_with: "ends",
  ">": ">",
  ">=": "≥",
  "<": "<",
  "<=": "≤",
  is_empty: "empty",
  is_not_empty: "not empty",
};

export const TAGS_OPERATORS: FilterOperator[] = [
  "contains",
  "not_contains",
  "=",
];

export const FEEDBACK_SCORE_OPERATORS: FilterOperator[] = [
  ">=",
  "<=",
  "=",
  "is_not_empty",
  "is_empty",
];

export const DICTIONARY_OPERATORS: FilterOperator[] = [
  "=",
  "contains",
  "not_contains",
  "starts_with",
  "ends_with",
  ">",
  "<",
];

export const operatorNeedsValue = (op: FilterOperator | ""): boolean =>
  !!op && !NO_VALUE_OPERATORS.includes(op as FilterOperator);

export const getOperatorLabel = (op: FilterOperator): string =>
  OPERATOR_LABELS[op] ?? op;

export const getOperatorShortLabel = (op: FilterOperator): string =>
  OPERATOR_SHORT_LABELS[op] ?? OPERATOR_LABELS[op] ?? op;
