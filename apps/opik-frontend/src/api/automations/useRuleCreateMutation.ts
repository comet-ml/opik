import { useMutation, useQueryClient } from "@tanstack/react-query";
import get from "lodash/get";
import api, { AUTOMATIONS_KEY, AUTOMATIONS_REST_ENDPOINT } from "@/api/api";
import { EvaluatorsRule } from "@/types/automations";
import { AxiosError } from "axios";
import { useToast } from "@/components/ui/use-toast";

type UseRuleCreateMutationParams = {
  rule: Partial<EvaluatorsRule>;
};

const useRuleCreateMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({ rule }: UseRuleCreateMutationParams) => {
      // Clean schema to remove frontend-only fields like 'unsaved'
      const cleanedRule = {
        ...rule,
        code: rule.code
          ? {
              ...rule.code,
              // Only process schema if it exists (LLM judge rules)
              ...("schema" in rule.code && rule.code.schema
                ? {
                    schema: rule.code.schema.map((item) => {
                      // eslint-disable-next-line @typescript-eslint/no-unused-vars
                      const { unsaved, ...rest } = item;
                      return rest;
                    }),
                  }
                : {}),
            }
          : rule.code,
      };

      const { data } = await api.post(
        `${AUTOMATIONS_REST_ENDPOINT}evaluators`,
        cleanedRule,
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

export default useRuleCreateMutation;
