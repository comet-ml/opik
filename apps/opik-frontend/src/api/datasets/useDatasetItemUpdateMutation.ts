import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";
import get from "lodash/get";
import api, { DATASETS_REST_ENDPOINT } from "@/api/api";
import { useToast } from "@/components/ui/use-toast";

type UseDatasetItemUpdateMutationParams = {
  itemId: string;
  tags: string[];
};

const useDatasetItemUpdateMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({
      itemId,
      tags,
    }: UseDatasetItemUpdateMutationParams) => {
      const { data } = await api.patch(
        `${DATASETS_REST_ENDPOINT}items/${itemId}`,
        { tags },
      );

      return data;
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
    onSettled: (data, error, variables) => {
      // Invalidate both the individual item and the list queries
      queryClient.invalidateQueries({
        queryKey: ["datasetItem", { datasetItemId: variables.itemId }],
      });
      queryClient.invalidateQueries({
        queryKey: ["datasetItems"],
      });
    },
  });
};

export default useDatasetItemUpdateMutation;
