import { CHART_TYPE } from "@/constants/chart";
import { METRIC_NAME_TYPE } from "@/api/projects/useProjectMetric";
import { ProjectMetricsWidget } from "@/types/dashboard";

const DEFAULT_TITLE = "Project metrics";

const METRIC_LABELS: Record<string, string> = {
  [METRIC_NAME_TYPE.FEEDBACK_SCORES]: "Trace metrics",
  [METRIC_NAME_TYPE.TRACE_COUNT]: "Number of traces",
  [METRIC_NAME_TYPE.TRACE_DURATION]: "Trace duration",
  [METRIC_NAME_TYPE.TOKEN_USAGE]: "Token usage",
  [METRIC_NAME_TYPE.COST]: "Estimated cost",
  [METRIC_NAME_TYPE.FAILED_GUARDRAILS]: "Failed guardrails",
  [METRIC_NAME_TYPE.THREAD_COUNT]: "Number of threads",
  [METRIC_NAME_TYPE.THREAD_DURATION]: "Thread duration",
  [METRIC_NAME_TYPE.THREAD_FEEDBACK_SCORES]: "Thread metrics",
  [METRIC_NAME_TYPE.SPAN_COUNT]: "Number of spans",
  [METRIC_NAME_TYPE.SPAN_DURATION]: "Span duration",
  [METRIC_NAME_TYPE.SPAN_FEEDBACK_SCORES]: "Span metrics",
  [METRIC_NAME_TYPE.SPAN_TOKEN_USAGE]: "Span token usage",
};

const FEEDBACK_SCORE_METRIC_TYPES = [
  METRIC_NAME_TYPE.FEEDBACK_SCORES,
  METRIC_NAME_TYPE.THREAD_FEEDBACK_SCORES,
  METRIC_NAME_TYPE.SPAN_FEEDBACK_SCORES,
];

const calculateProjectMetricsTitle = (
  config: Record<string, unknown>,
): string => {
  const widgetConfig = config as ProjectMetricsWidget["config"];
  const metricType = widgetConfig.metricType;

  if (!metricType) {
    return DEFAULT_TITLE;
  }

  const baseTitle = METRIC_LABELS[metricType] || DEFAULT_TITLE;

  if (
    FEEDBACK_SCORE_METRIC_TYPES.includes(metricType as METRIC_NAME_TYPE) &&
    widgetConfig.feedbackScores?.length === 1
  ) {
    return `${baseTitle} - ${widgetConfig.feedbackScores[0]}`;
  }

  return baseTitle;
};

export const widgetHelpers = {
  getDefaultConfig: () => ({
    chartType: CHART_TYPE.line,
  }),
  calculateTitle: calculateProjectMetricsTitle,
};
