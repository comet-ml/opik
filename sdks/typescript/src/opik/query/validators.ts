/**
 * Validation logic for OQL parser
 */

import { COLUMNS, SUPPORTED_OPERATORS, QUERY_CONFIG } from "./constants";

/**
 * Validates that a field exists in the schema
 */
export function validateFieldExists(field: string): void {
  const isSpecialField = QUERY_CONFIG.SPECIAL_FIELDS.includes(field);
  const isDefinedField = Object.keys(COLUMNS).includes(field);

  if (!isSpecialField && !isDefinedField) {
    const supportedFields = Object.keys(COLUMNS).join(", ");
    throw new Error(
      `Field ${field} is not supported, only the fields ${supportedFields} are supported.`
    );
  }
}

/**
 * Validates that a key is supported for the given field
 */
export function validateFieldKey(field: string, key: string): void {
  if (!QUERY_CONFIG.SPECIAL_FIELDS.includes(field)) {
    const supportedFields = Object.keys(COLUMNS).join(", ");
    throw new Error(
      `Field ${field}.${key} is not supported, only the fields ${supportedFields} are supported.`
    );
  }

  if (field === "usage" && !QUERY_CONFIG.USAGE_KEYS.includes(key)) {
    throw new Error(
      `When querying usage, ${key} is not supported, only usage.total_tokens, usage.prompt_tokens and usage.completion_tokens are supported.`
    );
  }
}

/**
 * Validates that an operator is supported for the given field
 */
export function validateOperator(field: string, operator: string): void {
  const supportedOps = SUPPORTED_OPERATORS[field];

  if (!supportedOps?.includes(operator)) {
    const operatorsList = supportedOps?.join(", ") || "none";
    throw new Error(
      `Operator ${operator} is not supported for field ${field}, only the operators ${operatorsList} are supported.`
    );
  }
}

/**
 * Validates and returns the connector type
 * @returns true if parsing should continue (AND), false if done
 * @throws Error if connector is invalid or OR
 */
export function validateConnector(connector: string): boolean {
  const lowerConnector = connector.toLowerCase();

  if (lowerConnector === "and") {
    return true;
  }

  if (lowerConnector === "or") {
    throw new Error("Invalid filter string, OR is not currently supported");
  }

  throw new Error(`Invalid filter string, trailing characters ${connector}`);
}

/**
 * Validates that a closing quote exists
 */
export function validateClosingQuote(
  hasClosingQuote: boolean,
  position: number,
  context: string
): void {
  if (!hasClosingQuote) {
    throw new Error(`Missing closing quote for: ${context}`);
  }
}

/**
 * Validates that a value has the correct format
 */
export function validateValueFormat(value: string, position: number): void {
  if (!value) {
    throw new Error(
      `Invalid value at position ${position}, expected a string in double quotes("value") or a number`
    );
  }
}
