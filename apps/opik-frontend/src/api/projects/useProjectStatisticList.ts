import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, {
  PROJECT_STATISTICS_KEY,
  PROJECTS_REST_ENDPOINT,
  QueryConfig,
} from "@/api/api";
import { ProjectStatistic } from "@/types/projects";
import { generateLogsSourceFilter, processFilters } from "@/lib/filters";
import { processSorting } from "@/lib/sorting";
import { Sorting } from "@/types/sorting";
import { LOGS_SOURCE } from "@/types/traces";

type UseProjectStatisticsListParams = {
  workspaceName: string;
  search?: string;
  sorting?: Sorting;
  page: number;
  size: number;
  logsSource?: LOGS_SOURCE;
};

type UseProjectStatisticsListResponse = {
  content: ProjectStatistic[];
  total: number;
};

export const PROJECT_STATS_WINDOW_DAYS = 30;

const getProjectStatisticsList = async (
  { signal }: QueryFunctionContext,
  {
    workspaceName,
    search,
    sorting,
    size,
    page,
    logsSource,
  }: UseProjectStatisticsListParams,
) => {
  const now = new Date();
  const fromTime = new Date(
    now.getTime() - PROJECT_STATS_WINDOW_DAYS * 24 * 60 * 60 * 1000,
  );

  const { data } = await api.get(`${PROJECTS_REST_ENDPOINT}stats`, {
    signal,
    params: {
      workspace_name: workspaceName,
      ...processSorting(sorting),
      ...(search && { name: search }),
      ...processFilters(
        undefined,
        logsSource ? generateLogsSourceFilter(logsSource) : undefined,
      ),
      from_time: fromTime.toISOString(),
      to_time: now.toISOString(),
      size,
      page,
    },
  });

  return data;
};

export default function useProjectStatisticsList(
  params: UseProjectStatisticsListParams,
  options?: QueryConfig<UseProjectStatisticsListResponse>,
) {
  return useQuery({
    queryKey: [PROJECT_STATISTICS_KEY, params],
    queryFn: (context) => getProjectStatisticsList(context, params),
    ...options,
  });
}
