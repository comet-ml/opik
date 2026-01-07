import { useMutation, useQueryClient } from "@tanstack/react-query";
import get from "lodash/get";
import api, { DATASETS_REST_ENDPOINT } from "@/api/api";
import { DatasetItem } from "@/types/datasets";
import { AxiosError } from "axios";
import { useToast } from "@/components/ui/use-toast";

type UseDatasetItemBatchMutationParams = {
  datasetId: string;
  datasetItems: Partial<DatasetItem>[];
  workspaceName: string;
};

type MutationContext = {
  queryKey: [string, { datasetId: string }];
};

const useDatasetItemBatchMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation<
    void,
    AxiosError,
    UseDatasetItemBatchMutationParams,
    MutationContext
  >({
    mutationFn: async ({
      datasetId,
      datasetItems,
      workspaceName,
    }: UseDatasetItemBatchMutationParams) => {
      await api.put(`${DATASETS_REST_ENDPOINT}items`, {
        dataset_id: datasetId,
        items: datasetItems,
        workspace_name: workspaceName,
      });
    },
    onMutate: async (params: UseDatasetItemBatchMutationParams) => {
      return {
        queryKey: ["dataset-items", { datasetId: params.datasetId }],
      };
    },
    onError: (error: AxiosError) => {
      const message = get(
        error,
        ["response", "data", "message"],
        error.message,
      );

      toast({
        title: "Error",
        description: message,
        variant: "destructive",
      });
    },
    onSettled: (data, error, variables, context) => {
      if (context) {
        queryClient.invalidateQueries({ queryKey: context.queryKey });
      }
      return queryClient.invalidateQueries({
        queryKey: ["datasets"],
      });
    },
  });
};

export default useDatasetItemBatchMutation;
