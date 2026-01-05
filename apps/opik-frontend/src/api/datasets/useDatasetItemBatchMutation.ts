import { useMutation, useQueryClient } from "@tanstack/react-query";
import get from "lodash/get";
import api, { DATASETS_REST_ENDPOINT } from "@/api/api";
import { DatasetItem, DatasetVersion } from "@/types/datasets";
import { AxiosError } from "axios";
import { useToast } from "@/components/ui/use-toast";

type UseDatasetItemBatchMutationParams = {
  datasetId: string;
  datasetItems: Partial<DatasetItem>[];
  workspaceName: string;
};

type DatasetVersionSummary = Pick<
  DatasetVersion,
  "id" | "version_hash" | "version_name" | "tags" | "change_description"
>;

type MutationContext = {
  queryKey: [string, { datasetId: string }];
};

const useDatasetItemBatchMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation<
    DatasetVersionSummary | undefined,
    AxiosError,
    UseDatasetItemBatchMutationParams,
    MutationContext
  >({
    mutationFn: async ({
      datasetId,
      datasetItems,
      workspaceName,
    }: UseDatasetItemBatchMutationParams) => {
      const { data } = await api.put<DatasetVersionSummary>(
        `${DATASETS_REST_ENDPOINT}items`,
        {
          dataset_id: datasetId,
          items: datasetItems,
          workspace_name: workspaceName,
        },
        {
          params: {
            respond_with_latest_version: true,
          },
        },
      );
      return data;
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
