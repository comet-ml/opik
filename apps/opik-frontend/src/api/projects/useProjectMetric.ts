import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { PROJECTS_REST_ENDPOINT, QueryConfig } from "@/api/api";
import { ProjectMetricTrace } from "@/types/projects";
import { Filter } from "@/types/filters";
import { processFiltersArray } from "@/lib/filters";

export enum METRIC_NAME_TYPE {
  // Trace metrics
  FEEDBACK_SCORES = "FEEDBACK_SCORES",
  TRACE_COUNT = "TRACE_COUNT",
  TRACE_DURATION = "DURATION",
  TOKEN_USAGE = "TOKEN_USAGE",
  COST = "COST",
  FAILED_GUARDRAILS = "GUARDRAILS_FAILED_COUNT",
  ERROR_COUNT = "ERROR_COUNT",
  COMPLETION_TOKENS = "COMPLETION_TOKENS",
  PROMPT_TOKENS = "PROMPT_TOKENS",
  TOTAL_TOKENS = "TOTAL_TOKENS",
  INPUT_COUNT = "INPUT_COUNT",
  OUTPUT_COUNT = "OUTPUT_COUNT",
  METADATA_COUNT = "METADATA_COUNT",
  TAGS_AVERAGE = "TAGS_AVERAGE",
  TRACE_WITH_ERRORS_PERCENT = "TRACE_WITH_ERRORS_PERCENT",
  GUARDRAILS_PASS_RATE = "GUARDRAILS_PASS_RATE",
  AVG_COST_PER_TRACE = "AVG_COST_PER_TRACE",
  
  // Span metrics (aggregated at trace level)
  SPAN_COUNT = "SPAN_COUNT",
  LLM_SPAN_COUNT = "LLM_SPAN_COUNT",
  SPAN_DURATION = "SPAN_DURATION",
  
  // Span metrics (direct span-level)
  SPAN_TOTAL_COUNT = "SPAN_TOTAL_COUNT",
  SPAN_ERROR_COUNT = "SPAN_ERROR_COUNT",
  SPAN_INPUT_COUNT = "SPAN_INPUT_COUNT",
  SPAN_OUTPUT_COUNT = "SPAN_OUTPUT_COUNT",
  SPAN_METADATA_COUNT = "SPAN_METADATA_COUNT",
  SPAN_TAGS_AVERAGE = "SPAN_TAGS_AVERAGE",
  SPAN_COST = "SPAN_COST",
  SPAN_AVG_COST = "SPAN_AVG_COST",
  SPAN_FEEDBACK_SCORES = "SPAN_FEEDBACK_SCORES",
  SPAN_TOKEN_USAGE = "SPAN_TOKEN_USAGE",
  SPAN_PROMPT_TOKENS = "SPAN_PROMPT_TOKENS",
  SPAN_COMPLETION_TOKENS = "SPAN_COMPLETION_TOKENS",
  SPAN_TOTAL_TOKENS = "SPAN_TOTAL_TOKENS",
  
  // Thread metrics
  THREAD_COUNT = "THREAD_COUNT",
  THREAD_DURATION = "THREAD_DURATION",
  THREAD_FEEDBACK_SCORES = "THREAD_FEEDBACK_SCORES",
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
  traceFilters?: Filter[];
  threadFilters?: Filter[];
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
  }: UseProjectMetricsParams,
) => {
  const { data } = await api.post<ProjectMetricsResponse>(
    `${PROJECTS_REST_ENDPOINT}${projectId}/metrics`,
    {
      metric_type: metricName,
      interval,
      interval_start: intervalStart,
      interval_end: intervalEnd,
      trace_filters: traceFilters
        ? processFiltersArray(traceFilters)
        : undefined,
      thread_filters: threadFilters
        ? processFiltersArray(threadFilters)
        : undefined,
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
