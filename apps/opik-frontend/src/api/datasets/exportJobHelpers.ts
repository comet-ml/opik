import { BASE_API_URL, DATASETS_REST_ENDPOINT } from "@/api/api";

/**
 * Returns the full download URL for an export job.
 * @param jobId - The ID of the export job
 * @returns The complete download URL for the export job
 */
export function getExportJobDownloadUrl(jobId: string): string {
  return `${BASE_API_URL}${DATASETS_REST_ENDPOINT}export-jobs/${jobId}/download`;
}
