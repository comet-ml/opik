/**
 * Constants and configuration for OQL parser
 */

/**
 * Predefined operator sets for reuse across entity-specific configs
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
  LIST_OPS: [
    "=",
    "!=",
    "contains",
    "not_contains",
    "is_empty",
    "is_not_empty",
  ],
  DICT_OPS: ["=", "contains", ">", "<"],
  LIMITED_STRING_OPS: ["=", "contains", "not_contains"],
  FEEDBACK_SCORES_OPS: [
    "=",
    "!=",
    ">",
    "<",
    ">=",
    "<=",
    "is_empty",
    "is_not_empty",
  ],
} as const;

/**
 * Operators that don't require a value
 */
export const OPERATORS_WITHOUT_VALUES = ["is_empty", "is_not_empty"] as const;
