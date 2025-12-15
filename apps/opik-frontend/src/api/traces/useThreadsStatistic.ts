import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { QueryConfig, TRACES_REST_ENDPOINT } from "@/api/api";
import { ColumnsStatistic } from "@/types/shared";
import { Filters } from "@/types/filters";
import { generateSearchByIDFilters, processFilters } from "@/lib/filters";

type UseThreadsStatisticParams = {
  projectId: string;
  filters?: Filters;
  search?: string;
  fromTime?: string;
  toTime?: string;
};

export type UseThreadsStatisticResponse = {
  stats: ColumnsStatistic;
};

const getThreadsStatistic = async (
  { signal }: QueryFunctionContext,
  { projectId, filters, search, fromTime, toTime }: UseThreadsStatisticParams,
) => {
  const { data } = await api.get<UseThreadsStatisticResponse>(
    `${TRACES_REST_ENDPOINT}threads/stats`,
    {
      signal,
      params: {
        project_id: projectId,
        ...processFilters(filters, generateSearchByIDFilters(search)),
        ...(fromTime && { from_time: fromTime }),
        ...(toTime && { to_time: toTime }),
      },
    },
  );

  return data;
};

export default function useThreadsStatistic(
  params: UseThreadsStatisticParams,
  options?: QueryConfig<UseThreadsStatisticResponse>,
) {
  return useQuery({
    queryKey: ["threads-statistic", params],
    queryFn: (context) => getThreadsStatistic(context, params),
    ...options,
  });
}
