import React, { useMemo } from "react";
import { Coins } from "lucide-react";

import {
  KPICard,
  MetricKPICard,
  getMetricKPICardConfigs,
} from "@/components/pages-shared/experiments/KPICard/KPICard";
import {
  formatAsDuration,
  formatAsCurrency,
} from "@/lib/optimization-formatters";
import { Experiment } from "@/types/datasets";
import { AggregatedCandidate } from "@/types/optimizations";

const CANDIDATE_KEY_MAP: Record<string, keyof AggregatedCandidate> = {
  score: "score",
  latency: "latencyP50",
  cost: "runtimeCost",
};

type OptimizationKPICardsProps = {
  experiments: Experiment[];
  baselineCandidate?: AggregatedCandidate;
  bestCandidate?: AggregatedCandidate;
  isEvaluationSuite?: boolean;
};

const OptimizationKPICards: React.FunctionComponent<
  OptimizationKPICardsProps
> = ({ experiments, baselineCandidate, bestCandidate, isEvaluationSuite }) => {
  const kpiData = useMemo(() => {
    const totalOptCost = experiments.reduce(
      (sum, e) => sum + (e.total_estimated_cost ?? 0),
      0,
    );

    let totalDuration: number | undefined;
    if (experiments.length >= 2) {
      const sorted = experiments
        .slice()
        .sort((a, b) => a.created_at.localeCompare(b.created_at));
      const first = new Date(sorted[0].created_at).getTime();
      const last = new Date(sorted[sorted.length - 1].created_at).getTime();
      totalDuration = (last - first) / 1000;
    }

    return { totalOptCost, totalDuration };
  }, [experiments]);

  const configs = getMetricKPICardConfigs({ isEvaluationSuite });

  return (
    <div className="grid grid-cols-4 gap-4">
      {configs.map((config) => {
        const field = CANDIDATE_KEY_MAP[config.key];
        return (
          <MetricKPICard
            key={config.key}
            icon={config.icon}
            label={config.label}
            baseline={baselineCandidate?.[field] as number | undefined}
            current={bestCandidate?.[field] as number | undefined}
            formatter={config.formatter}
            trend={config.trend}
          />
        );
      })}

      <KPICard icon={Coins} label="Optimization cost">
        <div className="flex items-baseline gap-1.5">
          <span className="comet-body-s-accented">
            {kpiData.totalOptCost > 0
              ? formatAsCurrency(kpiData.totalOptCost)
              : "-"}
          </span>
          {kpiData.totalDuration != null && kpiData.totalDuration > 0 && (
            <span className="comet-body-xs text-muted-slate">
              {formatAsDuration(kpiData.totalDuration)} total
            </span>
          )}
        </div>
      </KPICard>
    </div>
  );
};

export default OptimizationKPICards;
