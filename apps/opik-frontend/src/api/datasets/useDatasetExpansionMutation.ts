import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";

import api, { DATASETS_REST_ENDPOINT } from "@/api/api";
import {
  DatasetExpansionRequest,
  DatasetExpansionResponse,
} from "@/types/datasets";
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
        `${DATASETS_REST_ENDPOINT}${datasetId}/expansions`,
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
      const errorData = error?.response?.data as {
        message?: string;
        detail?: string;
      };

      let message =
        errorData?.message || errorData?.detail || "Failed to expand dataset";

      // Handle specific model not supported error
      if (message.includes("model not supported")) {
        const modelMatch = message.match(/model not supported (.+)/);
        const modelName = modelMatch ? modelMatch[1] : "selected model";
        message = `The ${modelName} is not supported by the backend. Please select a different model from the dropdown.`;
      }

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
