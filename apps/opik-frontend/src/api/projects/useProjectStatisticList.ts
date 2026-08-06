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
  windowDays?: number;
};

type UseProjectStatisticsListResponse = {
  content: ProjectStatistic[];
  total: number;
};

const getProjectStatisticsList = async (
  { signal }: QueryFunctionContext,
  {
    workspaceName,
    search,
    sorting,
    size,
    page,
    logsSource,
    windowDays,
  }: UseProjectStatisticsListParams,
) => {
  // Opt-in rolling window. Based on windowParams only if present for compatibility with v1.
  const now = Date.now();
  const windowParams =
    windowDays != null
      ? {
          from_time: new Date(
            now - windowDays * 24 * 60 * 60 * 1000,
          ).toISOString(),
          to_time: new Date(now).toISOString(),
        }
      : undefined;

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
      ...windowParams,
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
