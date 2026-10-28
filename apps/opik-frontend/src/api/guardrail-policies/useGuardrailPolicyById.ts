import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, {
  GUARDRAIL_POLICIES_REST_ENDPOINT,
  GUARDRAIL_POLICY_KEY,
  QueryConfig,
} from "@/api/api";
import { GuardrailPolicy } from "@/types/guardrails";

type UseGuardrailPolicyByIdParams = {
  policyId: string;
};

const getGuardrailPolicyById = async (
  { signal }: QueryFunctionContext,
  { policyId }: UseGuardrailPolicyByIdParams,
) => {
  const { data } = await api.get<GuardrailPolicy>(
    `${GUARDRAIL_POLICIES_REST_ENDPOINT}${policyId}`,
    { signal },
  );

  return data;
};

export default function useGuardrailPolicyById(
  params: UseGuardrailPolicyByIdParams,
  options?: QueryConfig<GuardrailPolicy>,
) {
  return useQuery({
    queryKey: [GUARDRAIL_POLICY_KEY, params],
    queryFn: (context) => getGuardrailPolicyById(context, params),
    ...options,
  });
}
