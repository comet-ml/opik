/**
 * Error messages for dataset operations.
 * Centralizing messages here makes localization and maintenance easier.
 */
export const commonErrorMessages = {
  JSON_NOT_ARRAY: (type?: string) =>
    `JSON input must be an array of objects${type ? ` (${type})` : ""}`,
  JSON_PARSE_ERROR: "Failed to parse JSON input",
  JSON_ITEM_NOT_OBJECT: (index: number, receivedType?: string) =>
    `Item at position ${index} is not an object (${receivedType})`,
};
