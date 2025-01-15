import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, {
  AUTOMATIONS_KEY,
  AUTOMATIONS_REST_ENDPOINT,
  QueryConfig,
} from "@/api/api";
import { EvaluatorsRule } from "@/types/automations";

type UseRulesListParams = {
  projectId: string;
  search?: string;
  page: number;
  size: number;
};

export type UseRulesListResponse = {
  content: EvaluatorsRule[];
  total: number;
};

const getRulesList = async (
  { signal }: QueryFunctionContext,
  { projectId, search, size, page }: UseRulesListParams,
) => {
  const { data } = await api.get<UseRulesListResponse>(
    `${AUTOMATIONS_REST_ENDPOINT}projects/${projectId}/evaluators`,
    {
      signal,
      params: {
        ...(search && { name: search }),
        size,
        page,
      },
    },
  );

  return data;
};

export default function useRulesList(
  params: UseRulesListParams,
  options?: QueryConfig<UseRulesListResponse>,
) {
  return useQuery({
    queryKey: [AUTOMATIONS_KEY, params],
    queryFn: (context) => getRulesList(context, params),
    ...options,
  });
}
