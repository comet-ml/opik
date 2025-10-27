import isArray from "lodash/isArray";
import toString from "lodash/toString";

/**
 * Options for array parsing and formatting
 */
export interface ArrayParseOptions {
  /**
   * Whether to convert array items to strings using toString
   */
  convertToString?: boolean;
  /**
   * Custom formatter for array items
   * @param item The array item
   * @param index The item index
   * @returns The formatted string representation
   */
  itemFormatter?: (item: unknown, index: number) => string;
}

/**
 * Default options for array parsing
 */
const DEFAULT_OPTIONS: Required<ArrayParseOptions> = {
  convertToString: true,
  itemFormatter: (item: unknown, index: number) =>
    `${index + 1}. ${toString(item)}`,
};

/**
 * Attempts to parse a string as a JavaScript array and format it as an ordered list
 * @param content The string content to parse
 * @param options Configuration options for parsing and formatting
 * @returns The formatted array content as a string, or null if parsing fails
 */
export const parseArrayFromString = (
  content: string,
  options: ArrayParseOptions = {},
): string | null => {
  const opts = { ...DEFAULT_OPTIONS, ...options };
  const trimmedContent = content.trim();

  // Check if the string looks like a JavaScript array representation
  if (!trimmedContent.startsWith("[") || !trimmedContent.endsWith("]")) {
    return null;
  }

  try {
    // Try to parse as JSON first (handles double quotes)
    const parsedArray = JSON.parse(trimmedContent);
    if (isArray(parsedArray)) {
      return parsedArray
        .map((item, index) => opts.itemFormatter(item, index))
        .join("\n");
    }
  } catch {
    // If JSON parsing fails, try to parse as JavaScript array with single quotes
    try {
      // NOTE: This only replaces single quotes with double quotes.
      // It does NOT attempt to quote object keys, which may cause parsing to fail for unquoted keys.
      // For more robust parsing, consider using a library like 'json5'.
      const safeContent = trimmedContent.replace(/'/g, '"'); // Replace single quotes with double quotes

      const parsedArray = JSON.parse(safeContent);
      if (isArray(parsedArray)) {
        return parsedArray
          .map((item, index) => opts.itemFormatter(item, index))
          .join("\n");
      }
    } catch {
      // If all parsing attempts fail, return null
    }
  }

  return null;
};

/**
 * Formats an array as an ordered list
 * @param array The array to format
 * @param options Configuration options for formatting
 * @returns The formatted array content as a string
 */
export const formatArrayAsOrderedList = (
  array: unknown[],
  options: ArrayParseOptions = {},
): string => {
  const opts = { ...DEFAULT_OPTIONS, ...options };
  return array.map((item, index) => opts.itemFormatter(item, index)).join("\n");
};

/**
 * Checks if a string looks like a JavaScript array representation
 * @param content The string content to check
 * @returns True if the string looks like an array
 */
export const isArrayLikeString = (content: string): boolean => {
  const trimmed = content.trim();

  // Check for [object Object] which indicates an object was stringified
  if (trimmed === "[object Object]") {
    return false;
  }

  return trimmed.startsWith("[") && trimmed.endsWith("]");
};
