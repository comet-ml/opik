/**
 * Type definitions for OQL (Opik Query Language) parser
 */

/**
 * Supported column types in OQL queries
 */
export type ColumnType =
  | "string"
  | "date_time"
  | "dictionary"
  | "feedback_scores_number"
  | "list"
  | "number";

/**
 * Parsed filter expression structure
 */
export interface FilterExpression {
  field: string;
  key?: string;
  operator: string;
  value: string;
  type?: ColumnType;
}

/**
 * Token representing a parsed field (simple or nested)
 */
export type FieldToken =
  | {
      type: "simple";
      field: string;
      columnType: ColumnType;
    }
  | {
      type: "nested";
      field: string;
      key: string;
      columnType: ColumnType;
    };

/**
 * Token representing a parsed operator
 */
export interface OperatorToken {
  operator: string;
}

/**
 * Token representing a parsed value
 */
export interface ValueToken {
  value: string;
}
