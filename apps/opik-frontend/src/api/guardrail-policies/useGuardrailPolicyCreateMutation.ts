import { useMutation, useQueryClient } from "@tanstack/react-query";
import api, {
  GUARDRAIL_POLICIES_KEY,
  GUARDRAIL_POLICIES_REST_ENDPOINT,
} from "@/api/api";
import { GuardrailPolicy } from "@/types/guardrails";
import { extractIdFromLocation } from "@/lib/utils";

export type GuardrailPolicyPayload = Pick<
  GuardrailPolicy,
  "name" | "execution_scope" | "rules"
> &
  Partial<Pick<GuardrailPolicy, "description">>;

type UseGuardrailPolicyCreateMutationParams = {
  policy: GuardrailPolicyPayload;
};

/**
 * No default error handler: a duplicate name has to show on the name field rather than as a
 * toast, so the caller presents errors.
 */
const useGuardrailPolicyCreateMutation = () => {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async ({ policy }: UseGuardrailPolicyCreateMutationParams) => {
      const { headers } = await api.post(
        GUARDRAIL_POLICIES_REST_ENDPOINT,
        policy,
      );

      return { id: extractIdFromLocation(headers?.location) };
    },
    onSettled: () => {
      return queryClient.invalidateQueries({
        queryKey: [GUARDRAIL_POLICIES_KEY],
      });
    },
  });
};

export default useGuardrailPolicyCreateMutation;
