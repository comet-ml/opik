import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, {
  PROJECT_STATISTICS_KEY,
  PROJECTS_REST_ENDPOINT,
  QueryConfig,
} from "@/api/api";
import { ProjectStatistic } from "@/types/projects";
import { processSorting } from "@/lib/sorting";
import { Sorting } from "@/types/sorting";

type UseProjectStatisticsListParams = {
  workspaceName: string;
  search?: string;
  sorting?: Sorting;
  page: number;
  size: number;
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
  }: UseProjectStatisticsListParams,
) => {
  const { data } = await api.get(`${PROJECTS_REST_ENDPOINT}stats`, {
    signal,
    params: {
      workspace_name: workspaceName,
      ...processSorting(sorting),
      ...(search && { name: search }),
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
