import { useMutation, useQueryClient } from "@tanstack/react-query";
import get from "lodash/get";
import { useToast } from "@/components/ui/use-toast";
import api, { DATASETS_REST_ENDPOINT } from "@/api/api";

type UseDatasetItemBatchDeleteMutationParams = {
  ids: string[];
};

const useDatasetItemBatchDeleteMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({ ids }: UseDatasetItemBatchDeleteMutationParams) => {
      const { data } = await api.post(`${DATASETS_REST_ENDPOINT}items/delete`, {
        item_ids: ids,
      });
      return data;
    },
    onSuccess: (_, { ids }) => {
      const isSingle = ids.length === 1;
      toast({
        title: isSingle ? "Dataset item removed" : "Dataset items removed",
        description: isSingle
          ? "The dataset item has been removed. Don't forget to save your changes to create a new version."
          : "The dataset items have been removed. Don't forget to save your changes to create a new version.",
      });
    },
    onError: (error) => {
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
    onSettled: () => {
      return queryClient.invalidateQueries({
        queryKey: ["dataset-items"],
      });
    },
  });
};

export default useDatasetItemBatchDeleteMutation;
