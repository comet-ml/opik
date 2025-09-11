import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";

import api, { DATASETS_REST_ENDPOINT } from "@/api/api";
import { DatasetExpansionRequest, DatasetExpansionResponse } from "@/types/datasets";
import { useToast } from "@/components/ui/use-toast";

type UseDatasetExpansionMutationParams = {
  datasetId: string;
} & DatasetExpansionRequest;

const useDatasetExpansionMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation<
    DatasetExpansionResponse,
    AxiosError,
    UseDatasetExpansionMutationParams
  >({
    mutationFn: async ({ datasetId, ...data }) => {
      const { data: response } = await api.post(
        `${DATASETS_REST_ENDPOINT}/${datasetId}/expand`,
        data,
      );
      return response;
    },
    onSuccess: () => {
      toast({
        title: "Dataset expansion successful",
        description: "Synthetic samples have been generated successfully",
      });
    },
    onError: (error) => {
      const message =
        (error?.response?.data as any)?.message ||
        (error?.response?.data as any)?.detail ||
        "Failed to expand dataset";

      toast({
        title: "Dataset expansion failed",
        description: message,
        variant: "destructive",
      });
    },
    onSettled: (data, error, variables) => {
      return queryClient.invalidateQueries({
        queryKey: ["dataset-items", { datasetId: variables.datasetId }],
      });
    },
  });
};

export default useDatasetExpansionMutation;