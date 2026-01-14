import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { PROJECTS_REST_ENDPOINT, QueryConfig } from "@/api/api";
import { ProjectMetricTrace } from "@/types/projects";
import { Filter } from "@/types/filters";
import { processFiltersArray } from "@/lib/filters";

export enum METRIC_NAME_TYPE {
  FEEDBACK_SCORES = "FEEDBACK_SCORES",
  TRACE_COUNT = "TRACE_COUNT",
  TRACE_DURATION = "DURATION",
  TOKEN_USAGE = "TOKEN_USAGE",
  COST = "COST",
  FAILED_GUARDRAILS = "GUARDRAILS_FAILED_COUNT",
  THREAD_COUNT = "THREAD_COUNT",
  THREAD_DURATION = "THREAD_DURATION",
  THREAD_FEEDBACK_SCORES = "THREAD_FEEDBACK_SCORES",
  SPAN_COUNT = "SPAN_COUNT",
  SPAN_DURATION = "SPAN_DURATION",
  SPAN_FEEDBACK_SCORES = "SPAN_FEEDBACK_SCORES",
  SPAN_TOKEN_USAGE = "SPAN_TOKEN_USAGE",
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
  intervalStart: string | undefined;
  intervalEnd: string | undefined;
  traceFilters?: Filter[];
  threadFilters?: Filter[];
  spanFilters?: Filter[];
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
    traceFilters,
    threadFilters,
    spanFilters,
  }: UseProjectMetricsParams,
) => {
  const { data } = await api.post<ProjectMetricsResponse>(
    `${PROJECTS_REST_ENDPOINT}${projectId}/metrics`,
    {
      metric_type: metricName,
      interval,
      ...(intervalStart && { interval_start: intervalStart }),
      ...(intervalEnd && { interval_end: intervalEnd }),
      trace_filters: traceFilters
        ? processFiltersArray(traceFilters)
        : undefined,
      thread_filters: threadFilters
        ? processFiltersArray(threadFilters)
        : undefined,
      span_filters: spanFilters ? processFiltersArray(spanFilters) : undefined,
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
