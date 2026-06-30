import {
  METRIC_TYPE,
  MetricParameters,
  Optimization,
} from "@/types/optimizations";
import { OPTIMIZATION_METRIC_OPTIONS } from "@/constants/optimizations";
import { getOptimizerLabel } from "@/lib/optimizations";

/** Human label for a metric type, falling back to the raw value then an em dash. */
export const getMetricLabel = (type?: string): string =>
  OPTIMIZATION_METRIC_OPTIONS.find((option) => option.value === type)?.label ??
  type ??
  "—";

export interface OptimizationMetricItem {
  type: METRIC_TYPE;
  label: string;
  parameters?: Partial<MetricParameters>;
}

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
