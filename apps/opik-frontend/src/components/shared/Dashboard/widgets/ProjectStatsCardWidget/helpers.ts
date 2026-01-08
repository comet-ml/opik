import { TRACE_DATA_TYPE } from "@/constants/traces";
import { ProjectStatsCardWidget } from "@/types/dashboard";

const DEFAULT_TITLE = "Project statistics";
const FEEDBACK_SCORE_PREFIX = "feedback_scores.";

const getMetricTitleMap = (source: TRACE_DATA_TYPE): Record<string, string> => {
  const isSpans = source === TRACE_DATA_TYPE.spans;
  const sourceLabel = isSpans ? "span" : "trace";
  const sourceLabelCap = isSpans ? "Span" : "Trace";

  return {
    trace_count: "Total trace count",
    thread_count: "Total thread count",
    span_count: isSpans ? "Total span count" : "Average span count",
    llm_span_count: "Average LLM span count",
    error_count: `Total ${sourceLabel} error count`,
    "duration.p50": `P50 ${sourceLabel} duration`,
    "duration.p90": `P90 ${sourceLabel} duration`,
    "duration.p99": `P99 ${sourceLabel} duration`,
    total_estimated_cost: isSpans ? "Avg cost per span" : "Avg cost per trace",
    total_estimated_cost_sum: `Total ${sourceLabel} cost sum`,
    "usage.completion_tokens": `${sourceLabelCap} output tokens (avg)`,
    "usage.prompt_tokens": `${sourceLabelCap} input tokens (avg)`,
    "usage.total_tokens": `${sourceLabelCap} total tokens (avg)`,
    input: `Total ${sourceLabel} input count`,
    output: `Total ${sourceLabel} output count`,
    metadata: `Total ${sourceLabel} metadata count`,
    tags: `Avg ${sourceLabel} tags count`,
    guardrails_failed_count: `Total ${sourceLabel} guardrails failed`,
  };
};

const calculateProjectStatsCardTitle = (
  config: Record<string, unknown>,
): string => {
  const widgetConfig = config as ProjectStatsCardWidget["config"];
  const source = widgetConfig.source;
  const metric = widgetConfig.metric;

  if (!metric) {
    return DEFAULT_TITLE;
  }

  if (metric.startsWith(FEEDBACK_SCORE_PREFIX)) {
    const scoreName = metric.replace(FEEDBACK_SCORE_PREFIX, "");
    const sourceLabel = source === TRACE_DATA_TYPE.spans ? "span" : "trace";
    return `Average ${sourceLabel} ${scoreName}`;
  }

  const metricTitleMap = getMetricTitleMap(source);
  return metricTitleMap[metric] || DEFAULT_TITLE;
};

export const widgetHelpers = {
  getDefaultConfig: () => ({
    source: TRACE_DATA_TYPE.traces,
  }),
  calculateTitle: calculateProjectStatsCardTitle,
};
