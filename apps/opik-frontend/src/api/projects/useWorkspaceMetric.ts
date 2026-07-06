import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { WORKSPACES_REST_ENDPOINT, QueryConfig } from "@/api/api";
import { BreakdownConfig, BREAKDOWN_FIELD } from "@/types/dashboard";
import {
  INTERVAL_TYPE,
  METRIC_NAME_TYPE,
  ProjectMetricsResponse,
} from "@/api/projects/useProjectMetric";

// The workspace endpoint only serves span metrics (provider/model breakdown is span-only)
export const WORKSPACE_METRIC_NAMES = [
  METRIC_NAME_TYPE.SPAN_COUNT,
  METRIC_NAME_TYPE.SPAN_TOKEN_USAGE,
  METRIC_NAME_TYPE.SPAN_COST,
] as const;

type UseWorkspaceMetricParams = {
  projectIds: string[];
  metricName: METRIC_NAME_TYPE;
  interval: INTERVAL_TYPE;
  intervalStart: string | undefined;
  intervalEnd: string | undefined;
  breakdown?: BreakdownConfig;
};

const processBreakdownConfig = (breakdown?: BreakdownConfig) => {
  if (!breakdown || breakdown.field === BREAKDOWN_FIELD.NONE) {
    return undefined;
  }

  return {
    field: breakdown.field,
    ...(breakdown.metadataKey && { metadata_key: breakdown.metadataKey }),
    ...(breakdown.subMetric && { sub_metric: breakdown.subMetric }),
  };
};

const getWorkspaceMetric = async (
  { signal }: QueryFunctionContext,
  {
    projectIds,
    metricName,
    interval,
    intervalStart,
    intervalEnd,
    breakdown,
  }: UseWorkspaceMetricParams,
) => {
  const { data } = await api.post<ProjectMetricsResponse>(
    `${WORKSPACES_REST_ENDPOINT}metrics/spans`,
    {
      metric_type: metricName,
      interval,
      ...(intervalStart && { interval_start: intervalStart }),
      ...(intervalEnd && { interval_end: intervalEnd }),
      // Empty => all projects in the workspace; otherwise only the selected set
      ...(projectIds.length > 0 && { project_ids: projectIds }),
      breakdown: processBreakdownConfig(breakdown),
    },
    {
      signal,
    },
  );

  return data;
};

const useWorkspaceMetric = (
  params: UseWorkspaceMetricParams,
  config?: QueryConfig<ProjectMetricsResponse>,
) => {
  return useQuery({
    queryKey: ["workspaceMetrics", params],
    queryFn: (context) => getWorkspaceMetric(context, params),
    ...config,
  });
};

export default useWorkspaceMetric;
