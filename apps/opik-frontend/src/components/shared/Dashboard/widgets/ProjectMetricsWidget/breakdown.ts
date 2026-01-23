/**
 * Group by field options for dashboard widget metrics.
 * Each field represents a dimension by which metrics can be grouped.
 *
 * Compatibility with metric types is based on entity type:
 * - Trace metrics: DURATION, TRACE_COUNT, TOKEN_USAGE, COST, FEEDBACK_SCORES, GUARDRAILS_FAILED_COUNT
 * - Thread metrics: THREAD_COUNT, THREAD_DURATION, THREAD_FEEDBACK_SCORES
 * - Span metrics: SPAN_COUNT, SPAN_DURATION, SPAN_TOKEN_USAGE, SPAN_FEEDBACK_SCORES
 *
 * This mirrors the backend BreakdownField enum in:
 * apps/opik-backend/src/main/java/com/comet/opik/api/metrics/BreakdownField.java
 */
export enum BREAKDOWN_FIELD {
  NONE = "none",
  TAGS = "tags",
  METADATA = "metadata",
  NAME = "name",
  ERROR_INFO = "error_info",
  MODEL = "model",
  PROVIDER = "provider",
  TYPE = "type",
}

/**
 * Display labels for group by fields.
 */
export const BREAKDOWN_FIELD_LABELS: Record<BREAKDOWN_FIELD, string> = {
  [BREAKDOWN_FIELD.NONE]: "No grouping",
  [BREAKDOWN_FIELD.TAGS]: "Tags",
  [BREAKDOWN_FIELD.METADATA]: "Metadata",
  [BREAKDOWN_FIELD.NAME]: "Name",
  [BREAKDOWN_FIELD.ERROR_INFO]: "Has Error",
  [BREAKDOWN_FIELD.MODEL]: "Model",
  [BREAKDOWN_FIELD.PROVIDER]: "Provider",
  [BREAKDOWN_FIELD.TYPE]: "Span Type",
};

/**
 * Special group names used in group by results.
 */
export const BREAKDOWN_GROUP_NAMES = {
  OTHERS: "__others__",
  OTHERS_DISPLAY: "Others",
  UNKNOWN: "Unknown",
};

/**
 * Metric types for compatibility checking.
 * These values must match METRIC_NAME_TYPE from useProjectMetric.ts
 */
const METRIC_TYPES = {
  // Trace metrics
  FEEDBACK_SCORES: "FEEDBACK_SCORES",
  TRACE_COUNT: "TRACE_COUNT",
  TRACE_DURATION: "DURATION",
  TOKEN_USAGE: "TOKEN_USAGE",
  COST: "COST",
  FAILED_GUARDRAILS: "GUARDRAILS_FAILED_COUNT",
  // Thread metrics
  THREAD_COUNT: "THREAD_COUNT",
  THREAD_DURATION: "THREAD_DURATION",
  THREAD_FEEDBACK_SCORES: "THREAD_FEEDBACK_SCORES",
  // Span metrics
  SPAN_COUNT: "SPAN_COUNT",
  SPAN_DURATION: "SPAN_DURATION",
  SPAN_TOKEN_USAGE: "SPAN_TOKEN_USAGE",
  SPAN_FEEDBACK_SCORES: "SPAN_FEEDBACK_SCORES",
} as const;

// Group metric types by entity for compatibility rules
const TRACE_METRICS = [
  METRIC_TYPES.FEEDBACK_SCORES,
  METRIC_TYPES.TRACE_COUNT,
  METRIC_TYPES.TRACE_DURATION,
  METRIC_TYPES.TOKEN_USAGE,
  METRIC_TYPES.COST,
  METRIC_TYPES.FAILED_GUARDRAILS,
];

const THREAD_METRICS = [
  METRIC_TYPES.THREAD_COUNT,
  METRIC_TYPES.THREAD_DURATION,
  METRIC_TYPES.THREAD_FEEDBACK_SCORES,
];

const SPAN_METRICS = [
  METRIC_TYPES.SPAN_COUNT,
  METRIC_TYPES.SPAN_DURATION,
  METRIC_TYPES.SPAN_TOKEN_USAGE,
  METRIC_TYPES.SPAN_FEEDBACK_SCORES,
];

const ALL_METRIC_TYPES = [...TRACE_METRICS, ...THREAD_METRICS, ...SPAN_METRICS];

/**
 * Compatibility matrix: which group by fields are compatible with which metric types.
 * Based on the Jira ticket OPIK-3790 "Supported Breakdown Fields" table:
 * - NONE: All metrics
 * - TAGS: Trace, Span, Thread
 * - METADATA: Trace, Span (not Thread)
 * - NAME: Trace, Span (not Thread)
 * - ERROR_INFO: Trace, Span (not Thread)
 * - MODEL: Spans only
 * - PROVIDER: Spans only
 * - TYPE: Spans only
 *
 * Note: PROJECT_ID is not supported as metrics are already project-scoped.
 */
export const BREAKDOWN_FIELD_COMPATIBILITY: Record<BREAKDOWN_FIELD, string[]> =
  {
    [BREAKDOWN_FIELD.NONE]: ALL_METRIC_TYPES,
    [BREAKDOWN_FIELD.TAGS]: ALL_METRIC_TYPES,
    [BREAKDOWN_FIELD.METADATA]: [...TRACE_METRICS, ...SPAN_METRICS],
    [BREAKDOWN_FIELD.NAME]: [...TRACE_METRICS, ...SPAN_METRICS],
    [BREAKDOWN_FIELD.ERROR_INFO]: [...TRACE_METRICS, ...SPAN_METRICS],
    [BREAKDOWN_FIELD.MODEL]: SPAN_METRICS,
    [BREAKDOWN_FIELD.PROVIDER]: SPAN_METRICS,
    [BREAKDOWN_FIELD.TYPE]: SPAN_METRICS,
  };

/**
 * Get all compatible group by fields for a given metric type.
 */
export function getCompatibleBreakdownFields(
  metricType: string,
): BREAKDOWN_FIELD[] {
  return Object.entries(BREAKDOWN_FIELD_COMPATIBILITY)
    .filter(([, compatibleMetrics]) => compatibleMetrics.includes(metricType))
    .map(([field]) => field as BREAKDOWN_FIELD);
}

/**
 * Group by field options for use in select components.
 */
export const BREAKDOWN_FIELD_OPTIONS = Object.values(BREAKDOWN_FIELD).map(
  (field) => ({
    value: field,
    label: BREAKDOWN_FIELD_LABELS[field],
    requiresKey: field === BREAKDOWN_FIELD.METADATA,
  }),
);
