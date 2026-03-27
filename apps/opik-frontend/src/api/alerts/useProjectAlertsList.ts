import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { PROJECTS_REST_ENDPOINT, QueryConfig } from "@/api/api";
import { AlertsListResponse } from "@/types/alerts";
import { Sorting } from "@/types/sorting";
import { Filter } from "@/types/filters";
import { processSorting } from "@/lib/sorting";
import { generateSearchByFieldFilters, processFilters } from "@/lib/filters";

type UseProjectAlertsListParams = {
  projectId: string;
  search?: string;
  filters?: Filter[];
  sorting?: Sorting;
  page: number;
  size: number;
};

const getProjectAlertsList = async (
  { signal }: QueryFunctionContext,
  {
    projectId,
    search,
    filters,
    sorting,
    size,
    page,
  }: UseProjectAlertsListParams,
) => {
  const { data } = await api.get<AlertsListResponse>(
    `${PROJECTS_REST_ENDPOINT}${projectId}/alerts`,
    {
      signal,
      params: {
        ...processFilters(
          filters,
          generateSearchByFieldFilters("name", search),
        ),
        ...processSorting(sorting),
        size,
        page,
      },
    },
  );

  return data;
};

export default function useProjectAlertsList(
  params: UseProjectAlertsListParams,
  options?: QueryConfig<AlertsListResponse>,
) {
  return useQuery({
    queryKey: ["project-alerts", params],
    queryFn: (context) => getProjectAlertsList(context, params),
    ...options,
  });
}
