import { useMutation } from "@tanstack/react-query";
import api, { DATASETS_REST_ENDPOINT } from "@/api/api";

type UseMarkExportJobViewedMutationParams = {
  jobId: string;
};

/**
 * Marks an export job as viewed by calling the backend endpoint.
 * This sets the viewed_at timestamp so the error toast won't show again.
 * This operation is idempotent.
 */
const markExportJobViewed = async ({
  jobId,
}: UseMarkExportJobViewedMutationParams): Promise<void> => {
  await api.put(`${DATASETS_REST_ENDPOINT}export-jobs/${jobId}/viewed`);
};

export default function useMarkExportJobViewedMutation() {
  return useMutation({
    mutationFn: markExportJobViewed,
  });
}
