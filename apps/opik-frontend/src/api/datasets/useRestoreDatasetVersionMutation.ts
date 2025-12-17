import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";
import get from "lodash/get";
import api, { DATASETS_REST_ENDPOINT } from "@/api/api";
import { DatasetVersion } from "@/types/datasets";
import { useToast } from "@/components/ui/use-toast";

type UseRestoreDatasetVersionMutationParams = {
  datasetId: string;
  versionRef: string;
  successMessage?: {
    title: string;
    description: string;
  };
};

const useRestoreDatasetVersionMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({
      datasetId,
      versionRef,
    }: UseRestoreDatasetVersionMutationParams) => {
      const { data } = await api.post<DatasetVersion>(
        `${DATASETS_REST_ENDPOINT}${datasetId}/versions/restore`,
        {
          version_ref: versionRef,
        },
      );

      return data;
    },
    onError: (error: AxiosError) => {
      const errors = get(error, ["response", "data", "errors"], []);
      const message =
        Array.isArray(errors) && errors.length > 0
          ? errors.join("; ")
          : get(error, ["response", "data", "message"], error.message) ||
            "Failed to restore version. Please try again.";

      toast({
        title: "Error",
        description: message,
        variant: "destructive",
      });
    },
    onSuccess: (_, { datasetId, successMessage }) => {
      toast({
        title: successMessage?.title ?? "Version restored",
        description:
          successMessage?.description ??
          "The dataset has been restored to the selected version.",
      });

      queryClient.invalidateQueries({
        queryKey: ["dataset", { datasetId }],
      });

      queryClient.invalidateQueries({
        queryKey: ["dataset-items", { datasetId }],
        exact: false,
      });

      queryClient.invalidateQueries({
        queryKey: ["dataset-versions", { datasetId }],
        exact: false,
      });

      queryClient.removeQueries({
        queryKey: ["dataset-item"],
      });
    },
  });
};

export default useRestoreDatasetVersionMutation;
