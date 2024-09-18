import { useMutation, useQueryClient } from "@tanstack/react-query";
import get from "lodash/get";
import { useToast } from "@/components/ui/use-toast";
import api, { EXPERIMENTS_REST_ENDPOINT } from "@/api/api";

type UseExperimentItemBatchDeleteMutationParams = {
  ids: string[];
};

const useExperimentItemBatchDeleteMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({ ids }: UseExperimentItemBatchDeleteMutationParams) => {
      const { data } = await api.post(
        `${EXPERIMENTS_REST_ENDPOINT}items/delete`,
        {
          ids: ids,
        },
      );
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
    onSettled: (data, error, variables, context) => {
      if (context) {
        return queryClient.invalidateQueries({
          queryKey: ["compare-experiments"],
        });
      }
    },
  });
};

export default useExperimentItemBatchDeleteMutation;
