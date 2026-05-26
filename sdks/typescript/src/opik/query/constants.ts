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
  // Operators supported by backend FieldType.STRING_STATE_DB. Mirrors
  // ANALYTICS_DB_OPERATOR_MAP in FilterQueryBuilder.java: STRING_STATE_DB has
  // entries only for CONTAINS, NOT_CONTAINS, STARTS_WITH, ENDS_WITH, EQUAL,
  // and NOT_EQUAL — > and < resolve to a null operator and produce a 400.
  STRING_STATE_DB_OPS: [
    "=",
    "!=",
    "contains",
    "not_contains",
    "starts_with",
    "ends_with",
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
  ENUM_OPS: ["=", "!=", "in", "not_in"],
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

/**
 * Operators that require an array value (e.g., `name in ["a", "b"]`)
 */
export const ARRAY_VALUE_OPERATORS = ["in", "not_in"] as const;
