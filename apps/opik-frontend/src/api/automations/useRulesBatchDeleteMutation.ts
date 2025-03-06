import { useMutation, useQueryClient } from "@tanstack/react-query";
import get from "lodash/get";
import { useToast } from "@/components/ui/use-toast";
import api, { AUTOMATIONS_KEY, AUTOMATIONS_REST_ENDPOINT } from "@/api/api";

type UseProjectBatchDeleteMutationParams = {
  ids: string[];
};

const useRulesBatchDeleteMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({ ids }: UseProjectBatchDeleteMutationParams) => {
      const { data } = await api.post(
        `${AUTOMATIONS_REST_ENDPOINT}evaluators/delete`,
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
    onSettled: () => {
      return queryClient.invalidateQueries({
        queryKey: [AUTOMATIONS_KEY],
      });
    },
  });
};

export default useRulesBatchDeleteMutation;
