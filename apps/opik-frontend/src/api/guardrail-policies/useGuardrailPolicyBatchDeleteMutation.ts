import { useMutation, useQueryClient } from "@tanstack/react-query";
import get from "lodash/get";
import { useToast } from "@/ui/use-toast";
import api, {
  GUARDRAIL_POLICIES_KEY,
  GUARDRAIL_POLICIES_REST_ENDPOINT,
  GUARDRAIL_POLICY_KEY,
} from "@/api/api";

type UseGuardrailPolicyBatchDeleteMutationParams = {
  ids: string[];
};

const useGuardrailPolicyBatchDeleteMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({
      ids,
    }: UseGuardrailPolicyBatchDeleteMutationParams) => {
      const { data } = await api.post(
        `${GUARDRAIL_POLICIES_REST_ENDPOINT}delete`,
        { ids },
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
    onSuccess: (_, { ids }) => {
      // A deleted policy reopened in the same session must not come back from the cache.
      ids.forEach((policyId) =>
        queryClient.removeQueries({
          queryKey: [GUARDRAIL_POLICY_KEY, { policyId }],
        }),
      );
    },
    onSettled: () => {
      return queryClient.invalidateQueries({
        queryKey: [GUARDRAIL_POLICIES_KEY],
      });
    },
  });
};

export default useGuardrailPolicyBatchDeleteMutation;
