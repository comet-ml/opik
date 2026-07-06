import useProjectMetric, {
  INTERVAL_TYPE,
  METRIC_NAME_TYPE,
  ProjectMetricsResponse,
} from "@/api/projects/useProjectMetric";
import useWorkspaceMetric from "@/api/projects/useWorkspaceMetric";
import { Filter } from "@/types/filters";
import { LOGS_SOURCE } from "@/types/traces";
import { BreakdownConfig } from "@/types/dashboard";
import { QueryConfig } from "@/api/api";

type UseMetricDataParams = {
  // Exactly one of projectId / projectIds identifies the scope.
  projectId?: string;
  projectIds?: string[];
  metricName: METRIC_NAME_TYPE;
  interval: INTERVAL_TYPE;
  intervalStart: string | undefined;
  intervalEnd: string | undefined;
  traceFilters?: Filter[];
  threadFilters?: Filter[];
  spanFilters?: Filter[];
  breakdown?: BreakdownConfig;
  logsSource?: LOGS_SOURCE;
};

// Picks the right metrics source for a dashboard metric widget: a single project uses the per-project endpoint (all
// metrics, filters, log source); a project set uses the workspace endpoint (span metrics aggregated across projects).
// Both return the same ProjectMetricsResponse shape, so callers consume one result regardless of scope.
const useMetricData = (
  params: UseMetricDataParams,
  config?: QueryConfig<ProjectMetricsResponse>,
) => {
  const isWorkspace = Array.isArray(params.projectIds);

  const projectQuery = useProjectMetric(
    {
      projectId: params.projectId ?? "",
      metricName: params.metricName,
      interval: params.interval,
      intervalStart: params.intervalStart,
      intervalEnd: params.intervalEnd,
      traceFilters: params.traceFilters,
      threadFilters: params.threadFilters,
      spanFilters: params.spanFilters,
      breakdown: params.breakdown,
      logsSource: params.logsSource,
    },
    {
      ...config,
      enabled: !isWorkspace && !!params.projectId && (config?.enabled ?? true),
    },
  );

  const workspaceQuery = useWorkspaceMetric(
    {
      projectIds: params.projectIds ?? [],
      metricName: params.metricName,
      interval: params.interval,
      intervalStart: params.intervalStart,
      intervalEnd: params.intervalEnd,
      breakdown: params.breakdown,
    },
    {
      ...config,
      enabled: isWorkspace && (config?.enabled ?? true),
    },
  );

  return isWorkspace ? workspaceQuery : projectQuery;
};

export default useMetricData;
