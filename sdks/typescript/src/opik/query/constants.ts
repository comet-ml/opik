/**
 * Constants and configuration for OQL parser
 */

import type { ColumnType } from "./types";

/**
 * Predefined operator sets for reuse
 */
export const OPERATOR_SETS = {
  STRING_OPS: [
    "=",
    "contains",
    "not_contains",
    "starts_with",
    "ends_with",
    "!=",
    ">",
    "<",
  ],
  NUMERIC_OPS: ["=", "!=", ">", "<", ">=", "<="],
  DATETIME_OPS: ["=", ">", "<", ">=", "<="],
  LIST_OPS: ["contains"],
  DICT_OPS: ["=", "contains", ">", "<"],
  LIMITED_STRING_OPS: ["=", "contains", "not_contains"],
} as const;

/**
 * Map of supported fields to their types
 */
export const COLUMNS: Record<string, ColumnType> = {
  id: "string",
  name: "string",
  status: "string",
  start_time: "date_time",
  end_time: "date_time",
  input: "string",
  output: "string",
  metadata: "dictionary",
  feedback_scores: "feedback_scores_number",
  tags: "list",
  "usage.total_tokens": "number",
  "usage.prompt_tokens": "number",
  "usage.completion_tokens": "number",
  duration: "number",
  number_of_messages: "number",
  created_by: "string",
  thread_id: "string",
  total_estimated_cost: "number",
  type: "string",
  model: "string",
  provider: "string",
};

/**
 * Supported operators for each field
 */
export const SUPPORTED_OPERATORS: Record<string, readonly string[]> = {
  id: OPERATOR_SETS.STRING_OPS,
  name: OPERATOR_SETS.STRING_OPS,
  status: OPERATOR_SETS.LIMITED_STRING_OPS,
  start_time: OPERATOR_SETS.DATETIME_OPS,
  end_time: OPERATOR_SETS.DATETIME_OPS,
  input: OPERATOR_SETS.LIMITED_STRING_OPS,
  output: OPERATOR_SETS.LIMITED_STRING_OPS,
  metadata: OPERATOR_SETS.DICT_OPS,
  feedback_scores: OPERATOR_SETS.NUMERIC_OPS,
  tags: OPERATOR_SETS.LIST_OPS,
  "usage.total_tokens": OPERATOR_SETS.NUMERIC_OPS,
  "usage.prompt_tokens": OPERATOR_SETS.NUMERIC_OPS,
  "usage.completion_tokens": OPERATOR_SETS.NUMERIC_OPS,
  duration: OPERATOR_SETS.NUMERIC_OPS,
  number_of_messages: OPERATOR_SETS.NUMERIC_OPS,
  created_by: OPERATOR_SETS.STRING_OPS,
  thread_id: OPERATOR_SETS.STRING_OPS,
  total_estimated_cost: OPERATOR_SETS.NUMERIC_OPS,
  type: OPERATOR_SETS.STRING_OPS,
  model: OPERATOR_SETS.STRING_OPS,
  provider: OPERATOR_SETS.STRING_OPS,
};

/**
 * Configuration for special field handling
 */
export const QUERY_CONFIG = {
  SPECIAL_FIELDS: ["usage", "metadata", "feedback_scores"] as readonly string[],
  USAGE_KEYS: [
    "total_tokens",
    "prompt_tokens",
    "completion_tokens",
  ] as readonly string[],
  QUOTE_CHARS: ['"', "'"] as const,
  CONNECTORS: {
    ALLOWED: ["and"] as readonly string[],
    FORBIDDEN: ["or"] as readonly string[],
  },
} as const;
