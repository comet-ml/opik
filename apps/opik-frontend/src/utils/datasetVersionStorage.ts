/**
 * Utility functions for parsing and formatting dataset version storage format.
 * Format: "datasetId::versionId"
 */

/**
 * Formats dataset ID and version ID into storage string format.
 * @param datasetId - The dataset ID
 * @param versionId - The version ID (UUID)
 * @returns Formatted string in format "datasetId::versionId"
 */
export const formatDatasetVersionKey = (
  datasetId: string,
  versionId: string,
): string => {
  return `${datasetId}::${versionId}`;
};

/**
 * Parses storage string format into dataset ID and version ID.
 * @param key - The storage key string in format "datasetId::versionId" or null
 * @returns Parsed object with datasetId and versionId, or null if invalid
 */
export const parseDatasetVersionKey = (
  key: string | null,
): { datasetId: string; versionId: string } | null => {
  if (!key) return null;
  const [datasetId, versionId] = key.split("::");
  if (!datasetId || !versionId) return null;
  return { datasetId, versionId };
};
