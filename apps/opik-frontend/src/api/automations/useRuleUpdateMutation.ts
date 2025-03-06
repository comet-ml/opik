import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";
import get from "lodash/get";

import api, { AUTOMATIONS_KEY, AUTOMATIONS_REST_ENDPOINT } from "@/api/api";
import { EvaluatorsRule } from "@/types/automations";
import { useToast } from "@/components/ui/use-toast";

type UseRuleUpdateMutationParams = {
  rule: Partial<EvaluatorsRule>;
  ruleId: string;
};

const useRuleUpdateMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({ rule, ruleId }: UseRuleUpdateMutationParams) => {
      const { data } = await api.patch(
        `${AUTOMATIONS_REST_ENDPOINT}evaluators/${ruleId}`,
        rule,
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
    onSettled: () => {
      return queryClient.invalidateQueries({
        queryKey: [AUTOMATIONS_KEY],
      });
    },
  });
};

export default useRuleUpdateMutation;
