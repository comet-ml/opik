import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { PROJECTS_REST_ENDPOINT, QueryConfig } from "@/api/api";
import { ProjectMetricTrace } from "@/types/projects";

export enum METRIC_NAME_TYPE {
  FEEDBACK_SCORES = "FEEDBACK_SCORES",
  TRACE_COUNT = "TRACE_COUNT",
  DURATION = "DURATION",
  TOKEN_USAGE = "TOKEN_USAGE",
  COST = "COST",
  FAILED_GUARDRAILS = "GUARDRAILS_FAILED_COUNT",
}

export enum INTERVAL_TYPE {
  HOURLY = "HOURLY",
  DAILY = "DAILY",
  WEEKLY = "WEEKLY",
}

type UseProjectMetricsParams = {
  projectId: string;
  metricName: METRIC_NAME_TYPE;
  interval: INTERVAL_TYPE;
  intervalStart: string;
  intervalEnd: string;
};

interface ProjectMetricsResponse {
  results: ProjectMetricTrace[];
}

const getProjectMetric = async (
  { signal }: QueryFunctionContext,
  {
    projectId,
    metricName,
    interval,
    intervalStart,
    intervalEnd,
  }: UseProjectMetricsParams,
) => {
  const { data } = await api.post<ProjectMetricsResponse>(
    `${PROJECTS_REST_ENDPOINT}${projectId}/metrics`,
    {
      metric_type: metricName,
      interval,
      interval_start: intervalStart,
      interval_end: intervalEnd,
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
