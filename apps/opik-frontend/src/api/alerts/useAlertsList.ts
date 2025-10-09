import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { ALERTS_KEY, ALERTS_REST_ENDPOINT, QueryConfig } from "@/api/api";
import { AlertsListResponse } from "@/types/alerts";
import { Sorting } from "@/types/sorting";
import { Filter } from "@/types/filters";
import { processSorting } from "@/lib/sorting";
import { generateSearchByFieldFilters, processFilters } from "@/lib/filters";

type UseAlertsListParams = {
  workspaceName?: string;
  search?: string;
  filters?: Filter[];
  sorting?: Sorting;
  page: number;
  size: number;
};

const getAlertsList = async (
  { signal }: QueryFunctionContext,
  { search, filters, sorting, size, page }: UseAlertsListParams,
) => {
  const { data } = await api.get<AlertsListResponse>(ALERTS_REST_ENDPOINT, {
    signal,
    params: {
      ...processFilters(filters, generateSearchByFieldFilters("name", search)),
      ...processSorting(sorting),
      size,
      page,
    },
  });

  return data;
};

export default function useAlertsList(
  params: UseAlertsListParams,
  options?: QueryConfig<AlertsListResponse>,
) {
  return useQuery({
    queryKey: [ALERTS_KEY, params],
    queryFn: (context) => getAlertsList(context, params),
    ...options,
  });
}
