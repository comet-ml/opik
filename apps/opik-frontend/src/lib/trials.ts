import { ExperimentItem } from "@/types/datasets";
import { TraceFeedbackScore } from "@/types/traces";
import { UsageData } from "@/types/shared";

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
  return {
    ...items[0],
    feedback_scores: aggregateFeedbackScores(items),
    duration: averageNumber(items.map((i) => i.duration)),
    total_estimated_cost: averageNumber(
      items.map((i) => i.total_estimated_cost),
    ),
    usage: averageUsage(items),
    trialCount: items.length,
    trialItems: items,
  };
};

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

const averageNumber = (values: (number | undefined)[]): number | undefined => {
  const valid = values.filter((v): v is number => v != null);
  if (valid.length === 0) return undefined;
  return valid.reduce((sum, v) => sum + v, 0) / valid.length;
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
