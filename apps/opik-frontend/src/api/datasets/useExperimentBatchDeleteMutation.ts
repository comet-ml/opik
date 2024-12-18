import { useMutation, useQueryClient } from "@tanstack/react-query";
import get from "lodash/get";
import { useToast } from "@/components/ui/use-toast";
import api, { EXPERIMENTS_REST_ENDPOINT } from "@/api/api";

type UseExperimentBatchDeleteMutationParams = {
  ids: string[];
};

const useExperimentBatchDeleteMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({ ids }: UseExperimentBatchDeleteMutationParams) => {
      const { data } = await api.post(`${EXPERIMENTS_REST_ENDPOINT}delete`, {
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
        queryKey: ["experiments"],
      });
    },
  });
};

export default useExperimentBatchDeleteMutation;
