import React, { useMemo } from "react";

import {
  MetricKPICard,
  getMetricKPICardConfigs,
} from "@/v1/pages-shared/experiments/KPICard/KPICard";
import { Experiment } from "@/types/datasets";
import {
  aggregateExperimentMetrics,
  AggregatedMetrics,
} from "@/lib/experiment-metrics";

type MetricValue = number | undefined;

type TrialKPICardsProps = {
  experiments: Experiment[];
  allOptimizationExperiments: Experiment[];
  objectiveName?: string;
  isEvaluationSuite?: boolean;
};

const getMetricValue = (
  metrics: AggregatedMetrics | undefined,
  key: string,
): MetricValue => {
  if (!metrics) return undefined;
  return metrics[key as keyof AggregatedMetrics] as MetricValue;
};

const TrialKPICards: React.FunctionComponent<TrialKPICardsProps> = ({
  experiments,
  allOptimizationExperiments,
  objectiveName,
  isEvaluationSuite,
}) => {
  const currentMetrics = useMemo(
    () => aggregateExperimentMetrics(experiments, objectiveName),
    [experiments, objectiveName],
  );

  const baselineMetrics = useMemo(() => {
    if (!allOptimizationExperiments.length) return undefined;

    const sorted = allOptimizationExperiments
      .slice()
      .sort((a, b) => a.created_at.localeCompare(b.created_at));

    const baselineExpIds = new Set<string>();
    const firstMeta = sorted[0]?.metadata as
      | Record<string, unknown>
      | undefined;
    const firstCandidateId = firstMeta?.candidate_id as string | undefined;

    if (firstCandidateId) {
      for (const exp of sorted) {
        const meta = exp.metadata as Record<string, unknown> | undefined;
        if (meta?.candidate_id === firstCandidateId) {
          baselineExpIds.add(exp.id);
        }
      }
    } else {
      baselineExpIds.add(sorted[0].id);
    }

    const isViewingBaseline = experiments.every((e) =>
      baselineExpIds.has(e.id),
    );
    if (isViewingBaseline) return undefined;

    const baselineExps = allOptimizationExperiments.filter((e) =>
      baselineExpIds.has(e.id),
    );
    return aggregateExperimentMetrics(baselineExps, objectiveName);
  }, [allOptimizationExperiments, experiments, objectiveName]);

  const configs = getMetricKPICardConfigs({ isEvaluationSuite, objectiveName });

  return (
    <div className="grid grid-cols-3 gap-4">
      {configs.map((config) => (
        <MetricKPICard
          key={config.key}
          icon={config.icon}
          label={config.label}
          baseline={getMetricValue(baselineMetrics, config.key)}
          current={getMetricValue(currentMetrics, config.key)}
          formatter={config.formatter}
          trend={config.trend}
        />
      ))}
    </div>
  );
};

export default TrialKPICards;
