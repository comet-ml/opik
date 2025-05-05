import { useMutation, useQueryClient } from "@tanstack/react-query";
import get from "lodash/get";
import { useToast } from "@/components/ui/use-toast";
import api, { OPTIMIZATIONS_KEY, OPTIMIZATIONS_REST_ENDPOINT } from "@/api/api";

type UseOptimizationBatchDeleteMutationParams = {
  ids: string[];
};

const useOptimizationBatchDeleteMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({ ids }: UseOptimizationBatchDeleteMutationParams) => {
      const { data } = await api.post(`${OPTIMIZATIONS_REST_ENDPOINT}delete`, {
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
      queryClient.invalidateQueries({ queryKey: ["datasets"] });
      return queryClient.invalidateQueries({
        queryKey: [OPTIMIZATIONS_KEY],
      });
    },
  });
};

export default useOptimizationBatchDeleteMutation;
