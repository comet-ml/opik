import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { PROJECTS_REST_ENDPOINT, QueryConfig } from "@/api/api";
import { Dashboard } from "@/types/dashboard";
import { Sorting } from "@/types/sorting";
import { processSorting } from "@/lib/sorting";
import { Filter } from "@/types/filters";
import { processFilters } from "@/lib/filters";

type UseProjectDashboardsListParams = {
  projectId: string;
  sorting?: Sorting;
  search?: string;
  filters?: Filter[];
  page: number;
  size: number;
};

type UseProjectDashboardsListResponse = {
  content: Dashboard[];
  sortable_by: string[];
  total: number;
};

const getProjectDashboardsList = async (
  { signal }: QueryFunctionContext,
  {
    projectId,
    sorting,
    search,
    filters,
    size,
    page,
  }: UseProjectDashboardsListParams,
) => {
  const { data } = await api.get(
    `${PROJECTS_REST_ENDPOINT}${projectId}/dashboards`,
    {
      signal,
      params: {
        ...processSorting(sorting),
        ...(search && { name: search }),
        ...processFilters(filters),
        size,
        page,
      },
    },
  );

  return data;
};

export default function useProjectDashboardsList(
  params: UseProjectDashboardsListParams,
  options?: QueryConfig<UseProjectDashboardsListResponse>,
) {
  return useQuery({
    queryKey: ["project-dashboards", params],
    queryFn: (context) => getProjectDashboardsList(context, params),
    ...options,
  });
}
