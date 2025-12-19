import { CHART_TYPE } from "@/constants/chart";
import { METRIC_NAME_TYPE } from "@/api/projects/useProjectMetric";
import { ProjectMetricsWidget } from "@/types/dashboard";

const DEFAULT_TITLE = "Project metrics";

const METRIC_LABELS: Record<string, string> = {
  [METRIC_NAME_TYPE.FEEDBACK_SCORES]: "Trace metrics",
  [METRIC_NAME_TYPE.TRACE_COUNT]: "Trace count",
  [METRIC_NAME_TYPE.TRACE_DURATION]: "Trace duration",
  [METRIC_NAME_TYPE.TOKEN_USAGE]: "Token usage",
  [METRIC_NAME_TYPE.COST]: "Estimated cost",
  [METRIC_NAME_TYPE.FAILED_GUARDRAILS]: "Failed guardrails",
  [METRIC_NAME_TYPE.THREAD_COUNT]: "Thread count",
  [METRIC_NAME_TYPE.THREAD_DURATION]: "Thread duration",
  [METRIC_NAME_TYPE.THREAD_FEEDBACK_SCORES]: "Thread metrics",
};

const calculateProjectMetricsTitle = (
  config: Record<string, unknown>,
): string => {
  const widgetConfig = config as ProjectMetricsWidget["config"];
  const metricType = widgetConfig.metricType;

  if (!metricType) {
    return DEFAULT_TITLE;
  }

  return METRIC_LABELS[metricType] || DEFAULT_TITLE;
};

export const widgetHelpers = {
  getDefaultConfig: () => ({
    chartType: CHART_TYPE.line,
  }),
  calculateTitle: calculateProjectMetricsTitle,
};
