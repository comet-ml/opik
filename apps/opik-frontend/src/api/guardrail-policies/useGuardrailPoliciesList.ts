import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, {
  GUARDRAIL_POLICIES_KEY,
  GUARDRAIL_POLICIES_REST_ENDPOINT,
  QueryConfig,
} from "@/api/api";
import { GuardrailPolicy } from "@/types/guardrails";
import { Sorting } from "@/types/sorting";
import { processSorting } from "@/lib/sorting";

type UseGuardrailPoliciesListParams = {
  // Not sent: the workspace travels in the request headers. It is part of the params so that
  // switching workspace produces a different query key.
  workspaceName: string;
  sorting?: Sorting;
  search?: string;
  page: number;
  size: number;
};

type UseGuardrailPoliciesListResponse = {
  content: GuardrailPolicy[];
  sortable_by: string[];
  total: number;
};

const getGuardrailPoliciesList = async (
  { signal }: QueryFunctionContext,
  { sorting, search, size, page }: UseGuardrailPoliciesListParams,
) => {
  const { data } = await api.get<UseGuardrailPoliciesListResponse>(
    GUARDRAIL_POLICIES_REST_ENDPOINT,
    {
      signal,
      params: {
        ...processSorting(sorting),
        ...(search && { name: search }),
        size,
        page,
      },
    },
  );

  return data;
};

export default function useGuardrailPoliciesList(
  params: UseGuardrailPoliciesListParams,
  options?: QueryConfig<UseGuardrailPoliciesListResponse>,
) {
  return useQuery({
    queryKey: [GUARDRAIL_POLICIES_KEY, params],
    queryFn: (context) => getGuardrailPoliciesList(context, params),
    ...options,
  });
}
