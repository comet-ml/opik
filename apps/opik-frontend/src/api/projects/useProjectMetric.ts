import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { DATASETS_REST_ENDPOINT, PROJECTS_REST_ENDPOINT } from "@/api/api";
import { processFilters } from "@/lib/filters";

type MetricNameType =
  | "FEEDBACK_SCORES"
  | "TRACE_COUNT"
  | "DURATION"
  | "TOKEN_USAGE";
type IntervalType = "HOURLY" | "DAILY" | "WEEKLY";

interface UseProjectMetricsParams {
  projectId: string;
  metricName: MetricNameType;
  interval: IntervalType;
  interval_start: string;
  interval_end: string;
}

const getProjectMetric = async (
  { signal }: QueryFunctionContext,
  {
    projectId,
    metricName,
    interval,
    interval_start,
    interval_end,
  }: UseProjectMetricsParams,
) => {
  const { data } = await api.post(
    `${PROJECTS_REST_ENDPOINT}${projectId}/metrics`,
    {
      metric_type: metricName,
      interval,
      interval_start,
      interval_end,
    },
    {
      signal,
    },
  );

  return data;
};

const useProjectMetric = (params: UseProjectMetricsParams) => {
  return useQuery({
    queryKey: ["projectMetrics", params],
    queryFn: (context) => getProjectMetric(context, params),
  });
};

export default useProjectMetric;
