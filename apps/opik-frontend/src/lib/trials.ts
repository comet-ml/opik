import { ExperimentItem } from "@/types/datasets";
import { TraceFeedbackScore } from "@/types/traces";
import { UsageData } from "@/types/shared";
import { formatNumericData } from "@/lib/utils";

export interface AggregatedFeedbackScore extends TraceFeedbackScore {
  stdDev: number;
  trialValues: number[];
}

export interface AggregatedExperimentItem extends ExperimentItem {
  trialCount: number;
  feedback_scores?: AggregatedFeedbackScore[];
  trialItems: ExperimentItem[];
}

export const isAggregatedScore = (
  score: TraceFeedbackScore | undefined,
): score is AggregatedFeedbackScore => {
  return !!score && "stdDev" in score;
};

export const isAggregatedItem = (
  item: ExperimentItem | undefined,
): item is AggregatedExperimentItem => {
  return (
    !!item &&
    "trialCount" in item &&
    (item as AggregatedExperimentItem).trialCount > 1
  );
};

export const aggregateTrialItems = (
  items: ExperimentItem[],
): AggregatedExperimentItem => {
  const sorted = [...items].sort((a, b) =>
    b.created_at.localeCompare(a.created_at),
  );
  const durations = items
    .map((i) => i.duration)
    .filter((v): v is number => v != null);
  const costs = items
    .map((i) => i.total_estimated_cost)
    .filter((v): v is number => v != null);

  return {
    ...sorted[0],
    feedback_scores: aggregateFeedbackScores(items),
    duration: averageNumber(durations),
    total_estimated_cost: averageNumber(costs),
    usage: averageUsage(items),
    trialCount: items.length,
    trialItems: items,
  };
};

export const getTrialAvgTooltip = (
  trialCount: number,
  stdDev?: number,
): string =>
  `Avg of ${trialCount} trials${
    stdDev != null ? ` (σ=${formatNumericData(stdDev)})` : ""
  }`;

const aggregateFeedbackScores = (
  items: ExperimentItem[],
): AggregatedFeedbackScore[] | undefined => {
  const scoresByName = new Map<string, TraceFeedbackScore[]>();

  for (const item of items) {
    for (const score of item.feedback_scores ?? []) {
      if (!scoresByName.has(score.name)) {
        scoresByName.set(score.name, []);
      }
      scoresByName.get(score.name)!.push(score);
    }
  }

  if (scoresByName.size === 0) return undefined;

  return Array.from(scoresByName.entries()).map(([name, scores]) => {
    const trialValues = scores.map((s) => s.value);
    const avg = trialValues.reduce((sum, v) => sum + v, 0) / trialValues.length;
    const variance =
      trialValues.reduce((sum, v) => sum + (v - avg) ** 2, 0) /
      trialValues.length;
    return {
      ...scores[0],
      name,
      value: avg,
      stdDev: Math.sqrt(variance),
      trialValues,
    };
  });
};

const averageNumber = (values: number[]): number | undefined => {
  if (values.length === 0) return undefined;
  return values.reduce((sum, v) => sum + v, 0) / values.length;
};

const averageUsage = (items: ExperimentItem[]): UsageData | undefined => {
  const usages = items.map((i) => i.usage).filter((u): u is UsageData => !!u);
  if (usages.length === 0) return undefined;
  const count = usages.length;
  const result = {} as UsageData;
  for (const key of Object.keys(usages[0]) as (keyof UsageData)[]) {
    result[key] = Math.round(usages.reduce((s, u) => s + u[key], 0) / count);
  }
  return result;
};
