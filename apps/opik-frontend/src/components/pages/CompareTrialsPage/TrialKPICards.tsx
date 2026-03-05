import React, { useMemo } from "react";
import { Clock, DollarSign, Target, Zap } from "lucide-react";

import MetricComparisonCell from "@/components/pages-shared/experiments/MetricComparisonCell/MetricComparisonCell";
import {
  formatAsPercentage,
  formatAsDuration,
  formatAsCurrency,
} from "@/lib/optimization-formatters";
import { Experiment } from "@/types/datasets";
import { getFeedbackScoreValue } from "@/lib/feedback-scores";

type TrialKPICardsProps = {
  experiments: Experiment[];
  allOptimizationExperiments: Experiment[];
  objectiveName?: string;
};

const aggregateExperiments = (
  experiments: Experiment[],
  objectiveName?: string,
) => {
  let totalWeightedScore = 0;
  let totalWeightedLatency = 0;
  let totalCost = 0;
  let totalTraceCount = 0;
  let hasScore = false;
  let hasCost = false;
  let hasLatency = false;

  for (const exp of experiments) {
    const tc = exp.trace_count || 0;
    totalTraceCount += tc;

    if (objectiveName) {
      const score =
        getFeedbackScoreValue(exp.feedback_scores ?? [], objectiveName) ??
        getFeedbackScoreValue(exp.experiment_scores ?? [], objectiveName);
      if (score != null) {
        totalWeightedScore += score * tc;
        hasScore = true;
      }
    }

    if (exp.total_estimated_cost != null) {
      totalCost += exp.total_estimated_cost;
      hasCost = true;
    }

    if (exp.duration?.p50 != null) {
      totalWeightedLatency += (exp.duration.p50 / 1000) * tc;
      hasLatency = true;
    }
  }

  return {
    score:
      hasScore && totalTraceCount > 0
        ? totalWeightedScore / totalTraceCount
        : undefined,
    latency:
      hasLatency && totalTraceCount > 0
        ? totalWeightedLatency / totalTraceCount
        : undefined,
    cost:
      hasCost && totalTraceCount > 0 ? totalCost / totalTraceCount : undefined,
    totalCost: hasCost ? totalCost : undefined,
    totalTraceCount,
  };
};

const TrialKPICards: React.FunctionComponent<TrialKPICardsProps> = ({
  experiments,
  allOptimizationExperiments,
  objectiveName,
}) => {
  const currentMetrics = useMemo(
    () => aggregateExperiments(experiments, objectiveName),
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
    return aggregateExperiments(baselineExps, objectiveName);
  }, [allOptimizationExperiments, experiments, objectiveName]);

  return (
    <div className="grid grid-cols-4 gap-4">
      <div className="rounded-lg border bg-muted/20 p-4">
        <div className="mb-2 flex items-center gap-2">
          <Target className="size-4 text-muted-slate" />
          <span className="comet-body-s text-muted-slate">
            {objectiveName ?? "Accuracy"}
          </span>
        </div>
        <MetricComparisonCell
          baseline={baselineMetrics?.score}
          current={currentMetrics.score}
          formatter={formatAsPercentage}
        />
      </div>

      <div className="rounded-lg border bg-muted/20 p-4">
        <div className="mb-2 flex items-center gap-2">
          <Zap className="size-4 text-muted-slate" />
          <span className="comet-body-s text-muted-slate">Latency</span>
        </div>
        <MetricComparisonCell
          baseline={baselineMetrics?.latency}
          current={currentMetrics.latency}
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
          baseline={baselineMetrics?.cost}
          current={currentMetrics.cost}
          formatter={formatAsCurrency}
          trend="inverted"
        />
      </div>

      <div className="rounded-lg border bg-muted/20 p-4">
        <div className="mb-2 flex items-center gap-2">
          <Clock className="size-4 text-muted-slate" />
          <span className="comet-body-s text-muted-slate">Traces</span>
        </div>
        <div className="flex flex-col gap-0.5">
          <span className="comet-body-s-accented">
            {currentMetrics.totalTraceCount > 0
              ? currentMetrics.totalTraceCount
              : "-"}
          </span>
          {currentMetrics.totalCost != null &&
            currentMetrics.totalCost > 0 &&
            currentMetrics.totalTraceCount > 0 && (
              <span className="comet-body-xs text-muted-slate">
                ~
                {formatAsCurrency(
                  currentMetrics.totalCost / currentMetrics.totalTraceCount,
                )}
                /trace
              </span>
            )}
        </div>
      </div>
    </div>
  );
};

export default TrialKPICards;
