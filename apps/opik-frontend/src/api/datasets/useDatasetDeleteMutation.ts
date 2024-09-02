import { useMutation, useQueryClient } from "@tanstack/react-query";
import get from "lodash/get";
import { useToast } from "@/components/ui/use-toast";
import api, { DATASETS_REST_ENDPOINT } from "@/api/api";
import { UseDatasetsListResponse } from "@/api/datasets/useDatasetsList";

type UseDatasetDeleteMutationParams = {
  datasetId: string;
  workspaceName: string;
};

const useDatasetDeleteMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({ datasetId }: UseDatasetDeleteMutationParams) => {
      const { data } = await api.delete(DATASETS_REST_ENDPOINT + datasetId);
      return data;
    },
    onMutate: async (params: UseDatasetDeleteMutationParams) => {
      const queryKey = ["datasets", { workspaceName: params.workspaceName }];

      await queryClient.cancelQueries({ queryKey });
      const previousDatasets: UseDatasetsListResponse | undefined =
        queryClient.getQueryData(queryKey);
      if (previousDatasets) {
        queryClient.setQueryData(queryKey, () => {
          return {
            ...previousDatasets,
            content: previousDatasets.content.filter(
              (p) => p.id !== params.datasetId,
            ),
          };
        });
      }

      return { previousDatasets, queryKey };
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
        queryClient.setQueryData(context.queryKey, context.previousDatasets);
      }
    },
    onSettled: (data, error, variables, context) => {
      if (context) {
        return queryClient.invalidateQueries({ queryKey: context.queryKey });
      }
    },
  });
};

export default useDatasetDeleteMutation;
