import { useMutation, useQueryClient } from "@tanstack/react-query";
import get from "lodash/get";
import { useToast } from "@/components/ui/use-toast";
import api, { DATASETS_REST_ENDPOINT } from "@/api/api";

type UseDatasetBatchDeleteMutationParams = {
  ids: string[];
};

const useDatasetBatchDeleteMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({ ids }: UseDatasetBatchDeleteMutationParams) => {
      const { data } = await api.post(`${DATASETS_REST_ENDPOINT}delete-batch`, {
        ids: ids,
      });
      return data;
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
        queryKey: ["datasets"],
      });
    },
  });
};

export default useDatasetBatchDeleteMutation;
