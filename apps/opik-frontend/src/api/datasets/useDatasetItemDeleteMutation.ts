import { useMutation, useQueryClient } from "@tanstack/react-query";
import get from "lodash/get";
import { useToast } from "@/components/ui/use-toast";
import api, { DATASETS_REST_ENDPOINT } from "@/api/api";
import { UseDatasetItemsListResponse } from "@/api/datasets/useDatasetItemsList";

type UseDatasetItemDeleteMutationParams = {
  datasetId: string;
  datasetItemId: string;
  workspaceName: string;
};

const useDatasetItemDeleteMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({
      datasetItemId,
    }: UseDatasetItemDeleteMutationParams) => {
      const { data } = await api.post(`${DATASETS_REST_ENDPOINT}items/delete`, {
        item_ids: [datasetItemId],
      });
      return data;
    },
    onMutate: async (params: UseDatasetItemDeleteMutationParams) => {
      const queryKey = ["dataset-items", { datasetId: params.datasetId }];

      await queryClient.cancelQueries({ queryKey });
      const previousDatasetItems: UseDatasetItemsListResponse | undefined =
        queryClient.getQueryData(queryKey);
      if (previousDatasetItems) {
        queryClient.setQueryData(queryKey, () => {
          return {
            ...previousDatasetItems,
            content: previousDatasetItems.content.filter(
              (p) => p.id !== params.datasetItemId,
            ),
          };
        });
      }

      return { previousDatasetItems, queryKey };
    },
    onError: (error, data, context) => {
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

      if (context) {
        queryClient.setQueryData(
          context.queryKey,
          context.previousDatasetItems,
        );
      }
    },
    onSettled: (data, error, variables, context) => {
      if (context) {
        return queryClient.invalidateQueries({ queryKey: context.queryKey });
      }
    },
  });
};

export default useDatasetItemDeleteMutation;
