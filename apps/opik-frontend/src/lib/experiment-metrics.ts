import { Experiment } from "@/types/datasets";
import { getObjectiveScoreValue } from "@/lib/feedback-scores";

export type AggregatedMetrics = {
  score: number | undefined;
  cost: number | undefined;
  totalCost: number | undefined;
  latency: number | undefined;
  totalTraceCount: number;
  totalDatasetItemCount: number;
  passedCount: number;
  totalCount: number;
};

export const aggregateExperimentMetrics = (
  experiments: Experiment[],
  objectiveName?: string,
): AggregatedMetrics => {
  let totalWeightedScore = 0;
  let totalWeightedLatency = 0;
  let totalCost = 0;
  let totalTraceCount = 0;
  let totalDatasetItemCount = 0;
  let totalPassedCount = 0;
  let totalTotalCount = 0;
  let hasScore = false;
  let hasCost = false;
  let hasLatency = false;

  let totalWeightedScoreItems = 0;

  for (const exp of experiments) {
    const tc = exp.trace_count || 0;
    const scoreWeight = exp.total_count ?? tc;
    totalTraceCount += tc;
    totalDatasetItemCount += exp.total_count ?? tc;

    if (exp.passed_count != null) {
      totalPassedCount += exp.passed_count;
    }
    if (exp.total_count != null) {
      totalTotalCount += exp.total_count;
    }

    if (objectiveName) {
      const score = getObjectiveScoreValue(exp, objectiveName);
      if (score != null) {
        totalWeightedScore += score * scoreWeight;
        totalWeightedScoreItems += scoreWeight;
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
      hasScore && totalWeightedScoreItems > 0
        ? totalWeightedScore / totalWeightedScoreItems
        : undefined,
    cost:
      hasCost && totalTraceCount > 0 ? totalCost / totalTraceCount : undefined,
    totalCost: hasCost ? totalCost : undefined,
    latency:
      hasLatency && totalTraceCount > 0
        ? totalWeightedLatency / totalTraceCount
        : undefined,
    totalTraceCount,
    totalDatasetItemCount,
    passedCount: totalPassedCount,
    totalCount: totalTotalCount,
  };
};
