import { useMutation, useQueryClient } from "@tanstack/react-query";
import api, { DATASETS_REST_ENDPOINT } from "@/api/api";

type UseDeleteDatasetExportJobMutationParams = {
  jobId: string;
};

/**
 * Deletes an export job and its associated file from storage.
 * This permanently removes the export file.
 */
const deleteExportJob = async ({
  jobId,
}: UseDeleteDatasetExportJobMutationParams): Promise<void> => {
  await api.delete(`${DATASETS_REST_ENDPOINT}export-jobs/${jobId}`);
};

export default function useDeleteDatasetExportJobMutation() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: deleteExportJob,
    onSuccess: (_, variables) => {
      // Invalidate the specific job query
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
