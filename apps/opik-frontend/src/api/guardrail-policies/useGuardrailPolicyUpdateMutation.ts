import { useMutation, useQueryClient } from "@tanstack/react-query";
import api, {
  GUARDRAIL_POLICIES_KEY,
  GUARDRAIL_POLICIES_REST_ENDPOINT,
  GUARDRAIL_POLICY_KEY,
} from "@/api/api";
import { GuardrailPolicyPayload } from "@/api/guardrail-policies/useGuardrailPolicyCreateMutation";

type UseGuardrailPolicyUpdateMutationParams = {
  policyId: string;
  policy: GuardrailPolicyPayload;
};

/**
 * The endpoint replaces the policy whole, rules included: a rule left out of the payload is
 * removed. Like create, it leaves error presentation to the caller.
 */
const useGuardrailPolicyUpdateMutation = () => {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async ({
      policyId,
      policy,
    }: UseGuardrailPolicyUpdateMutationParams) => {
      await api.put(`${GUARDRAIL_POLICIES_REST_ENDPOINT}${policyId}`, policy);
    },
    onSettled: () => {
      queryClient.invalidateQueries({ queryKey: [GUARDRAIL_POLICY_KEY] });
      return queryClient.invalidateQueries({
        queryKey: [GUARDRAIL_POLICIES_KEY],
      });
    },
  });
};

export default useGuardrailPolicyUpdateMutation;
