import React, { useMemo } from "react";
import { Clock, DollarSign, Target, Zap } from "lucide-react";

import MetricComparisonCell from "@/components/pages-shared/experiments/MetricComparisonCell/MetricComparisonCell";
import {
  formatAsPercentage,
  formatAsDuration,
  formatAsCurrency,
} from "@/lib/optimization-formatters";
import { Experiment } from "@/types/datasets";
import { AggregatedCandidate } from "@/types/optimizations";

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
    const baselineScore = baselineCandidate?.score;
    const bestScore = bestCandidate?.score;

    const baselineDuration = baselineCandidate?.latencyP50;
    const bestDuration = bestCandidate?.latencyP50;

    const baselineCost = baselineCandidate?.runtimeCost;
    const bestCost = bestCandidate?.runtimeCost;

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

    return {
      baselineScore,
      bestScore,
      baselineDuration,
      bestDuration,
      baselineCost,
      bestCost,
      totalOptCost,
      totalDuration,
    };
  }, [experiments, baselineCandidate, bestCandidate]);

  return (
    <div className="grid grid-cols-4 gap-4">
      <div className="rounded-lg border bg-muted/20 p-4">
        <div className="mb-2 flex items-center gap-2">
          <Target className="size-4 text-muted-slate" />
          <span className="comet-body-s text-muted-slate">
            {isEvaluationSuite ? "Pass rate" : "Accuracy"}
          </span>
        </div>
        <MetricComparisonCell
          baseline={kpiData.baselineScore}
          current={kpiData.bestScore}
          formatter={formatAsPercentage}
        />
      </div>

      <div className="rounded-lg border bg-muted/20 p-4">
        <div className="mb-2 flex items-center gap-2">
          <Zap className="size-4 text-muted-slate" />
          <span className="comet-body-s text-muted-slate">Latency</span>
        </div>
        <MetricComparisonCell
          baseline={kpiData.baselineDuration}
          current={kpiData.bestDuration}
          formatter={formatAsDuration}
          trend="inverted"
        />
      </div>

      <div className="rounded-lg border bg-muted/20 p-4">
        <div className="mb-2 flex items-center gap-2">
          <DollarSign className="size-4 text-muted-slate" />
          <span className="comet-body-s text-muted-slate">Runtime Cost</span>
        </div>
        <MetricComparisonCell
          baseline={kpiData.baselineCost}
          current={kpiData.bestCost}
          formatter={formatAsCurrency}
          trend="inverted"
        />
      </div>

      <div className="rounded-lg border bg-muted/20 p-4">
        <div className="mb-2 flex items-center gap-2">
          <Clock className="size-4 text-muted-slate" />
          <span className="comet-body-s text-muted-slate">
            Optimization Cost
          </span>
        </div>
        <div className="flex flex-col gap-0.5">
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
      </div>
    </div>
  );
};

export default OptimizationKPICards;
