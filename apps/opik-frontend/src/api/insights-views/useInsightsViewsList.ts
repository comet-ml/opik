import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, {
  INSIGHTS_VIEWS_KEY,
  INSIGHTS_VIEWS_REST_ENDPOINT,
  QueryConfig,
} from "@/api/api";
import { Dashboard } from "@/types/dashboard";
import { Sorting } from "@/types/sorting";
import { processSorting } from "@/lib/sorting";
import { Filter } from "@/types/filters";
import { processFilters } from "@/lib/filters";

type UseInsightsViewsListParams = {
  workspaceName: string;
  sorting?: Sorting;
  search?: string;
  filters?: Filter[];
  page: number;
  size: number;
};

type UseInsightsViewsListResponse = {
  content: Dashboard[];
  sortable_by: string[];
  total: number;
};

const getInsightsViewsList = async (
  { signal }: QueryFunctionContext,
  {
    workspaceName,
    sorting,
    search,
    filters,
    size,
    page,
  }: UseInsightsViewsListParams,
) => {
  const { data } = await api.get(INSIGHTS_VIEWS_REST_ENDPOINT, {
    signal,
    params: {
      workspace_name: workspaceName,
      ...processSorting(sorting),
      ...(search && { name: search }),
      ...processFilters(filters),
      size,
      page,
    },
  });

  return data;
};

export default function useInsightsViewsList(
  params: UseInsightsViewsListParams,
  options?: QueryConfig<UseInsightsViewsListResponse>,
) {
  return useQuery({
    queryKey: [INSIGHTS_VIEWS_KEY, params],
    queryFn: (context) => getInsightsViewsList(context, params),
    ...options,
  });
}
