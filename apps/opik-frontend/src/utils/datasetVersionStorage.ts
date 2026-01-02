/**
 * Utility functions for parsing and formatting dataset version storage format.
 * Format: "datasetId::versionHash"
 */

/**
 * Formats dataset ID and version hash into storage string format.
 * @param datasetId - The dataset ID
 * @param versionHash - The version hash
 * @returns Formatted string in format "datasetId::versionHash"
 */
export const formatDatasetVersionKey = (
  datasetId: string,
  versionHash: string,
): string => {
  return `${datasetId}::${versionHash}`;
};

/**
 * Parses storage string format into dataset ID and version hash.
 * @param key - The storage key string in format "datasetId::versionHash" or null
 * @returns Parsed object with datasetId and versionHash, or null if invalid
 */
export const parseDatasetVersionKey = (
  key: string | null,
): { datasetId: string; versionHash: string } | null => {
  if (!key) return null;
  const [datasetId, versionHash] = key.split("::");
  if (!datasetId || !versionHash) return null;
  return { datasetId, versionHash };
};
