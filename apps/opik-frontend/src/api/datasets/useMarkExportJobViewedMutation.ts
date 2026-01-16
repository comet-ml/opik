import { useMutation, useQueryClient } from "@tanstack/react-query";
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
  await api.put(`${DATASETS_REST_ENDPOINT}export-jobs/${jobId}/mark-viewed`);
};

export default function useMarkExportJobViewedMutation() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: markExportJobViewed,
    onSuccess: (_, variables) => {
      // Invalidate the specific job query to get updated viewed_at
      queryClient.invalidateQueries({
        queryKey: ["dataset-export-job", { jobId: variables.jobId }],
      });
      // Also invalidate the all jobs query to ensure consistency
      queryClient.invalidateQueries({
        queryKey: ["dataset-export-jobs"],
      });
    },
  });
}
