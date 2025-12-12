import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";
import get from "lodash/get";
import api, { DATASETS_REST_ENDPOINT } from "@/api/api";
import { DatasetVersion } from "@/types/datasets";
import { useToast } from "@/components/ui/use-toast";

type UseCommitDatasetVersionMutationParams = {
  datasetId: string;
  changeDescription?: string;
  tags?: string[];
  metadata?: Record<string, string>;
};

const useCommitDatasetVersionMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({
      datasetId,
      changeDescription,
      tags,
      metadata,
    }: UseCommitDatasetVersionMutationParams) => {
      const { data } = await api.post<DatasetVersion>(
        `${DATASETS_REST_ENDPOINT}${datasetId}/versions`,
        {
          change_description: changeDescription,
          tags,
          metadata,
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
            "Failed to save changes. Please try again.";

      toast({
        title: "Error",
        description: message,
        variant: "destructive",
      });
    },
    onSettled: (_, __, { datasetId }) => {
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
    },
  });
};

export default useCommitDatasetVersionMutation;
