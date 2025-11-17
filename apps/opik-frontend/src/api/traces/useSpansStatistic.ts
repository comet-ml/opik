import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { QueryConfig, SPANS_REST_ENDPOINT } from "@/api/api";
import { SPAN_TYPE } from "@/types/traces";
import { ColumnsStatistic } from "@/types/shared";
import { Filters } from "@/types/filters";
import { generateSearchByIDFilters, processFilters } from "@/lib/filters";

type UseSpansStatisticParams = {
  projectId: string;
  traceId?: string;
  type?: SPAN_TYPE;
  filters?: Filters;
  search?: string;
  fromTime?: string;
  toTime?: string;
};

export type UseSpansStatisticResponse = {
  stats: ColumnsStatistic;
};

const getSpansStatistic = async (
  { signal }: QueryFunctionContext,
  {
    projectId,
    traceId,
    type,
    filters,
    search,
    fromTime,
    toTime,
  }: UseSpansStatisticParams,
) => {
  const { data } = await api.get(`${SPANS_REST_ENDPOINT}stats`, {
    signal,
    params: {
      project_id: projectId,
      ...(traceId && { trace_id: traceId }),
      ...(type && { type }),
      ...processFilters(filters, generateSearchByIDFilters(search)),
      ...(fromTime && { from_time: fromTime }),
      ...(toTime && { to_time: toTime }),
    },
  });

  return data;
};

export default function useSpansStatistic(
  params: UseSpansStatisticParams,
  options?: QueryConfig<UseSpansStatisticResponse>,
) {
  return useQuery({
    queryKey: ["spans-statistic", params],
    queryFn: (context) => getSpansStatistic(context, params),
    ...options,
  });
}
