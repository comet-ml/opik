import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { PROJECTS_REST_ENDPOINT, QueryConfig } from "@/api/api";
import { ProjectMetricTrace } from "@/types/projects";

type MetricNameType =
  | "FEEDBACK_SCORES"
  | "TRACE_COUNT"
  | "DURATION"
  | "TOKEN_USAGE";
export type IntervalType = "HOURLY" | "DAILY" | "WEEKLY";

interface UseProjectMetricsParams {
  projectId: string;
  metricName: MetricNameType;
  interval: IntervalType;
  interval_start: string;
  interval_end: string;
}

interface ProjectMetricsResponse {
  results: ProjectMetricTrace[];
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
  const { data } = await api.post<ProjectMetricsResponse>(
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

  return data?.results;
};

const useProjectMetric = (
  params: UseProjectMetricsParams,
  config?: QueryConfig<ProjectMetricTrace[]>,
) => {
  return useQuery({
    queryKey: ["projectMetrics", params],
    queryFn: (context) => getProjectMetric(context, params),
    ...config,
  });
};

export default useProjectMetric;
