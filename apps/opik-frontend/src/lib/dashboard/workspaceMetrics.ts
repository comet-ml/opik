import { METRIC_NAME_TYPE } from "@/api/projects/useProjectMetric";
import { formatCost } from "@/lib/money";

// Metrics supported when aggregating across projects via the workspace endpoint
// (POST /v1/private/workspaces/metrics/spans). Provider/model breakdown is span-only, so only span metrics qualify.
export const WORKSPACE_METRIC_NAMES: METRIC_NAME_TYPE[] = [
  METRIC_NAME_TYPE.SPAN_COUNT,
  METRIC_NAME_TYPE.SPAN_TOKEN_USAGE,
  METRIC_NAME_TYPE.SPAN_COST,
  METRIC_NAME_TYPE.SPAN_DURATION,
];

export const isWorkspaceMetric = (metric?: string): boolean =>
  WORKSPACE_METRIC_NAMES.includes(metric as METRIC_NAME_TYPE);

// Metric options for the time-series widget editor when multiple projects are selected.
export const WORKSPACE_TIME_SERIES_METRIC_OPTIONS = [
  {
    value: METRIC_NAME_TYPE.SPAN_COUNT,
    label: "Number of spans",
    filterType: "span" as const,
  },
  {
    value: METRIC_NAME_TYPE.SPAN_TOKEN_USAGE,
    label: "Span token usage",
    filterType: "span" as const,
  },
  {
    value: METRIC_NAME_TYPE.SPAN_COST,
    label: "Span cost",
    filterType: "span" as const,
  },
  {
    value: METRIC_NAME_TYPE.SPAN_DURATION,
    label: "Duration",
    filterType: "span" as const,
  },
];

// Metric definitions for the single-metric (stat card) widget when multiple projects are selected. These render a
// single total from the workspace endpoint queried with interval=TOTAL, so they carry their own value formatters.
export type WorkspaceStatMetricDefinition = {
  value: METRIC_NAME_TYPE;
  label: string;
  formatter: (value: number) => string;
  tooltipFormatter?: (value: number) => string;
  // Token usage returns one value per usage key, so the card needs a selected key (e.g. total_tokens).
  requiresUsageKey?: boolean;
};

export const WORKSPACE_STAT_METRICS: WorkspaceStatMetricDefinition[] = [
  {
    value: METRIC_NAME_TYPE.SPAN_COUNT,
    label: "Total span count",
    formatter: (value: number) => value.toLocaleString(),
  },
  {
    value: METRIC_NAME_TYPE.SPAN_TOKEN_USAGE,
    label: "Total span token usage",
    formatter: (value: number) => value.toLocaleString(),
    requiresUsageKey: true,
  },
  {
    value: METRIC_NAME_TYPE.SPAN_COST,
    label: "Total span cost",
    formatter: formatCost,
    tooltipFormatter: (value: number) =>
      formatCost(value, { modifier: "full" }),
  },
];

export const WORKSPACE_STAT_METRIC_OPTIONS = WORKSPACE_STAT_METRICS.map(
  (m) => ({
    value: m.value,
    label: m.label,
  }),
);

export const getWorkspaceStatMetric = (
  metricValue?: string,
): WorkspaceStatMetricDefinition | null =>
  WORKSPACE_STAT_METRICS.find((m) => m.value === metricValue) || null;

export const DEFAULT_WORKSPACE_USAGE_METRIC = "total_tokens";

export type ResolvedProjectSelection = {
  // Single-project mode (per-project endpoints).
  projectId?: string;
  // Workspace mode (aggregate across projects). Present only when 2+ projects are selected.
  projectIds?: string[];
};

// Resolves a widget's project configuration into either a single project or a project set. A runtime project (e.g.
// the project Insights tab) always pins to a single project. Otherwise: one selected project => single; two or more
// => workspace aggregation; none => not configured. Falls back to the legacy single `projectId` field.
export const resolveProjectSelection = ({
  runtimeProjectId,
  projectId,
  projectIds,
}: {
  runtimeProjectId?: string;
  projectId?: string;
  projectIds?: string[];
}): ResolvedProjectSelection => {
  if (runtimeProjectId) {
    return { projectId: runtimeProjectId };
  }
  if (Array.isArray(projectIds)) {
    if (projectIds.length === 1) return { projectId: projectIds[0] };
    if (projectIds.length >= 2) return { projectIds };
    return {};
  }
  if (projectId) return { projectId };
  return {};
};

// Whether the current editor selection aggregates across projects (2+ projects and not pinned to a runtime project).
export const isMultiProjectSelection = (
  runtimeProjectId: string | undefined,
  projectIds: string[],
): boolean => !runtimeProjectId && projectIds.length >= 2;
