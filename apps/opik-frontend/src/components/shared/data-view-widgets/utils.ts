import { formatDate } from "@/lib/date";

const ISO_DATE_REGEXP = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}/;

function isIsoDateString(value: string): boolean {
  return ISO_DATE_REGEXP.test(value);
}

/**
 * Safely converts a value to a displayable string.
 * - Objects/arrays are JSON stringified (compact or pretty based on `compact` flag)
 * - Strings are returned as-is (ISO dates are formatted)
 * - null/undefined return empty string
 * - Other primitives are converted via String()
 */
export function toDisplayString(value: unknown, compact = true): string {
  if (value === null || value === undefined) {
    return "";
  }
  if (typeof value === "string") {
    if (isIsoDateString(value)) {
      return formatDate(value, { includeSeconds: true });
    }
    return value;
  }
  if (typeof value === "object") {
    try {
      return compact ? JSON.stringify(value) : JSON.stringify(value, null, 2);
    } catch {
      return "[Object]";
    }
  }
  return String(value);
}

/**
 * Checks if a value is JSON (object or array, not a string)
 */
export function isJsonValue(value: unknown): boolean {
  return (
    value !== null &&
    typeof value === "object" &&
    !(value instanceof Date) &&
    !(value instanceof RegExp)
  );
}
