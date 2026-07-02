import {
  METRIC_TYPE,
  MetricParameters,
  Optimization,
  OPTIMIZATION_STATUS,
} from "@/types/optimizations";
import { getMetricLabel, getOptimizerLabel } from "@/lib/optimizations";

export { getMetricLabel };

/**
 * Per-status dot colours for the header status pill. The pill chrome stays
 * neutral (Figma), so the dot alone carries the status colour. Keyed to the
 * same palette as STATUS_TO_VARIANT_MAP.
 */
export const STATUS_DOT_COLOR: Record<OPTIMIZATION_STATUS, string> = {
  [OPTIMIZATION_STATUS.RUNNING]: "var(--color-green)",
  [OPTIMIZATION_STATUS.COMPLETED]: "var(--color-gray)",
  [OPTIMIZATION_STATUS.CANCELLED]: "var(--color-red)",
  [OPTIMIZATION_STATUS.INITIALIZED]: "var(--color-blue)",
  [OPTIMIZATION_STATUS.ERROR]: "var(--color-red)",
};

export interface OptimizationMetricItem {
  type: METRIC_TYPE;
  label: string;
  parameters?: Partial<MetricParameters>;
}

/** Human labels for the metric parameter keys shown in the metric popover. */
export const METRIC_PARAMETER_LABELS: Record<string, string> = {
  reference_key: "Reference key",
  case_sensitive: "Case sensitive",
  task_introduction: "Task introduction",
  evaluation_criteria: "Evaluation criteria",
  code: "Code",
};

/** Render a metric parameter value for display: booleans as Yes/No, empty as an em dash. */
export const formatMetricParameterValue = (value: unknown): string => {
  if (typeof value === "boolean") return value ? "Yes" : "No";
  if (value === null || value === undefined || value === "") return "—";
  return String(value);
};

export interface OptimizationConfigItems {
  model?: string;
  algorithmLabel?: string;
  metric?: OptimizationMetricItem;
}

/**
 * Resolve the run-configuration items shown as header pills.
 *
 * Studio runs carry the full config under `studio_config`; SDK runs only have
 * `objective_name`, so the metric label falls back to that and the model /
 * algorithm pills are simply omitted.
 */
export const getOptimizationConfigItems = (
  optimization?: Optimization,
): OptimizationConfigItems => {
  const studioConfig = optimization?.studio_config;
  const metricConfig = studioConfig?.evaluation?.metrics?.[0];

  let metric: OptimizationMetricItem | undefined;
  if (metricConfig) {
    metric = {
      type: metricConfig.type,
      label: getMetricLabel(metricConfig.type),
      parameters: metricConfig.parameters,
    };
  } else if (optimization?.objective_name) {
    metric = {
      type: optimization.objective_name as METRIC_TYPE,
      label: getMetricLabel(optimization.objective_name),
    };
  }

  return {
    model: studioConfig?.llm_model?.model,
    algorithmLabel: studioConfig?.optimizer?.type
      ? getOptimizerLabel(studioConfig.optimizer.type)
      : undefined,
    metric,
  };
};
