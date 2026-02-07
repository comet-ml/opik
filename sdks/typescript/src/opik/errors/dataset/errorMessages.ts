/**
 * Error messages for dataset operations.
 * Centralizing messages here makes localization and maintenance easier.
 */
export const datasetErrorMessages = {
  DATASET_NOT_FOUND: (name: string) => `Dataset with name '${name}' not found`,
  DATASET_ITEM_NOT_FOUND: (id: string) =>
    `Dataset item with id '${id}' not found`,
  DATASET_ITEM_MISSING_ID: (index: number) =>
    `Item at index ${index} is missing an ID required for update`,
  INVALID_JSON_FORMAT: (details?: string) =>
    `Invalid JSON format${details ? ": " + details : ""}`,
  JSON_NOT_ARRAY: "JSON input must be an array of objects",
  ITEM_NOT_OBJECT: (index: number, type: string) =>
    `Item at position ${index} is not an object (${type})`,
  INSERTION_FAILED: (error: string) =>
    `Failed to insert items from JSON: ${error}`,
  DATASET_JSON_PARSE_ERROR: (error: string) =>
    `Failed to parse JSON input: ${error}`,
  DATASET_VERSION_NOT_FOUND: (versionName: string, datasetName: string) =>
    `Dataset version '${versionName}' not found in dataset '${datasetName}'`,
};
