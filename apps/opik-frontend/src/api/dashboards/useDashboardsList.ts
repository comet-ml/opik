import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, {
  DASHBOARDS_KEY,
  DASHBOARDS_REST_ENDPOINT,
  QueryConfig,
} from "@/api/api";
import { Dashboard } from "@/types/dashboard";
import { Sorting } from "@/types/sorting";
import { processSorting } from "@/lib/sorting";
import { Filter } from "@/types/filters";
import { processFilters } from "@/lib/filters";

type UseDashboardsListParams = {
  workspaceName: string;
  sorting?: Sorting;
  search?: string;
  filters?: Filter[];
  page: number;
  size: number;
};

type UseDashboardsListResponse = {
  content: Dashboard[];
  sortable_by: string[];
  total: number;
};

const getDashboardsList = async (
  { signal }: QueryFunctionContext,
  {
    workspaceName,
    sorting,
    search,
    filters,
    size,
    page,
  }: UseDashboardsListParams,
) => {
  const { data } = await api.get(DASHBOARDS_REST_ENDPOINT, {
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

export default function useDashboardsList(
  params: UseDashboardsListParams,
  options?: QueryConfig<UseDashboardsListResponse>,
) {
  return useQuery({
    queryKey: [DASHBOARDS_KEY, params],
    queryFn: (context) => getDashboardsList(context, params),
    ...options,
  });
}
