import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, {
  AUTOMATIONS_KEY,
  AUTOMATIONS_REST_ENDPOINT,
  QueryConfig,
} from "@/api/api";
import { EvaluatorsRule } from "@/types/automations";
import { Filters } from "@/types/filters";
import { processFilters } from "@/lib/filters";
import { Sorting } from "@/types/sorting";
import { processSorting } from "@/lib/sorting";

type UseRulesListParams = {
  workspaceName?: string;
  projectId?: string;
  filters?: Filters;
  sorting?: Sorting;
  search?: string;
  page: number;
  size: number;
};

export type UseRulesListResponse = {
  content: EvaluatorsRule[];
  sortable_by: string[];
  total: number;
};

const getRulesList = async (
  { signal }: QueryFunctionContext,
  { projectId, filters, sorting, search, size, page }: UseRulesListParams,
) => {
  const { data } = await api.get<UseRulesListResponse>(
    `${AUTOMATIONS_REST_ENDPOINT}evaluators`,
    {
      signal,
      params: {
        project_id: projectId,
        ...processFilters(filters),
        ...processSorting(sorting),
        ...(search && { id: search }),
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
