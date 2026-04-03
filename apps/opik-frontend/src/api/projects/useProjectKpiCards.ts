import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { PROJECTS_REST_ENDPOINT, QueryConfig } from "@/api/api";
import { Filters } from "@/types/filters";
import { generateLogsSourceFilter, processFilters } from "@/lib/filters";
import { LOGS_SOURCE } from "@/types/traces";

export type KpiEntityType = "traces" | "spans" | "threads";

export type KpiMetricType = "count" | "errors" | "avg_duration" | "total_cost";

export type KpiMetric = {
  type: KpiMetricType;
  current_value: number | null;
  previous_value: number | null;
};

export type KpiCardResponse = {
  stats: KpiMetric[];
};

type UseProjectKpiCardsParams = {
  projectId: string;
  entityType: KpiEntityType;
  filters?: Filters;
  intervalStart?: string;
  intervalEnd?: string;
  logsSource?: LOGS_SOURCE;
};

const getProjectKpiCards = async (
  { signal }: QueryFunctionContext,
  {
    projectId,
    entityType,
    filters,
    intervalStart,
    intervalEnd,
    logsSource,
  }: UseProjectKpiCardsParams,
) => {
  const processedFilters = processFilters(
    filters,
    logsSource ? generateLogsSourceFilter(logsSource) : undefined,
  );

  const { data } = await api.post<KpiCardResponse>(
    `${PROJECTS_REST_ENDPOINT}${projectId}/kpi-cards`,
    {
      entity_type: entityType,
      ...processedFilters,
      ...(intervalStart && { interval_start: intervalStart }),
      ...(intervalEnd && { interval_end: intervalEnd }),
    },
    { signal },
  );

  return data;
};

export default function useProjectKpiCards(
  params: UseProjectKpiCardsParams,
  options?: QueryConfig<KpiCardResponse>,
) {
  return useQuery({
    queryKey: ["project-kpi-cards", params],
    queryFn: (context) => getProjectKpiCards(context, params),
    ...options,
  });
}
