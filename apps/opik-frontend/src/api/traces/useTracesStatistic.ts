import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { QueryConfig, TRACES_REST_ENDPOINT } from "@/api/api";
import { ColumnsStatistic } from "@/types/shared";
import { Filters } from "@/types/filters";
import { generateSearchByIDFilters, processFilters } from "@/lib/filters";

type UseTracesStatisticParams = {
  projectId: string;
  filters?: Filters;
  search?: string;
  fromTime?: string;
  toTime?: string;
};

export type UseTracesStatisticResponse = {
  stats: ColumnsStatistic;
};

const getTracesStatistic = async (
  { signal }: QueryFunctionContext,
  { projectId, filters, search, fromTime, toTime }: UseTracesStatisticParams,
) => {
  const { data } = await api.get<UseTracesStatisticResponse>(
    `${TRACES_REST_ENDPOINT}stats`,
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
