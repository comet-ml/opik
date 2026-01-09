/**
 * Group by field options for dashboard widget metrics.
 * Each field represents a dimension by which metrics can be grouped.
 */
export enum BREAKDOWN_FIELD {
  NONE = "none",
  TAGS = "tags",
  METADATA = "metadata",
  NAME = "name",
  ERROR_INFO = "error_info",
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
  FEEDBACK_SCORES: "FEEDBACK_SCORES",
  TRACE_COUNT: "TRACE_COUNT",
  TRACE_DURATION: "DURATION",
  TOKEN_USAGE: "TOKEN_USAGE",
  COST: "COST",
  FAILED_GUARDRAILS: "GUARDRAILS_FAILED_COUNT",
  THREAD_COUNT: "THREAD_COUNT",
  THREAD_DURATION: "THREAD_DURATION",
  THREAD_FEEDBACK_SCORES: "THREAD_FEEDBACK_SCORES",
} as const;

const ALL_METRIC_TYPES = Object.values(METRIC_TYPES);

/**
 * Compatibility matrix: which group by fields are compatible with which metric types.
 */
export const BREAKDOWN_FIELD_COMPATIBILITY: Record<BREAKDOWN_FIELD, string[]> =
  {
    [BREAKDOWN_FIELD.NONE]: ALL_METRIC_TYPES,
    [BREAKDOWN_FIELD.TAGS]: ALL_METRIC_TYPES,
    [BREAKDOWN_FIELD.METADATA]: ALL_METRIC_TYPES,
    [BREAKDOWN_FIELD.NAME]: ALL_METRIC_TYPES,
    [BREAKDOWN_FIELD.ERROR_INFO]: ALL_METRIC_TYPES,
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

