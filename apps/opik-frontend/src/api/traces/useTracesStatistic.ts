import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { QueryConfig, TRACES_REST_ENDPOINT } from "@/api/api";
import { ColumnsStatistic } from "@/types/shared";
import { Filters } from "@/types/filters";
import {
  generateLogsSourceFilter,
  generateVisibilityFilters,
  processFilters,
} from "@/lib/filters";
import { LOGS_SOURCE, TRACE_VISIBILITY_MODE } from "@/types/traces";

type UseTracesStatisticParams = {
  projectId: string;
  filters?: Filters;
  search?: string;
  fromTime?: string;
  toTime?: string;
  logsSource?: LOGS_SOURCE;
  visibilityMode?: TRACE_VISIBILITY_MODE;
};

export type UseTracesStatisticResponse = {
  stats: ColumnsStatistic;
};

const getTracesStatistic = async (
  { signal }: QueryFunctionContext,
  {
    projectId,
    filters,
    search,
    fromTime,
    toTime,
    logsSource,
    visibilityMode,
  }: UseTracesStatisticParams,
) => {
  const { data } = await api.get<UseTracesStatisticResponse>(
    `${TRACES_REST_ENDPOINT}stats`,
    {
      signal,
      params: {
        project_id: projectId,
        // Opt-in, unlike useTracesList: this hook has never sent a visibility filter, and defaulting
        // one would silently drop hidden traces from every existing caller's aggregates.
        ...(visibilityMode ? generateVisibilityFilters(visibilityMode) : {}),
        ...processFilters(
          filters,
          logsSource ? generateLogsSourceFilter(logsSource) : undefined,
        ),
        ...(search && { search }),
        ...(fromTime && { from_time: fromTime }),
        ...(toTime && { to_time: toTime }),
      },
    },
  );

  return data;
};

export default function useTracesStatistic(
  params: UseTracesStatisticParams,
  options?: QueryConfig<UseTracesStatisticResponse>,
) {
  return useQuery({
    queryKey: ["traces-statistic", params],
    queryFn: (context) => getTracesStatistic(context, params),
    ...options,
  });
}
