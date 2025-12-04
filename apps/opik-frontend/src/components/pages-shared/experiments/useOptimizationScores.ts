import { useMemo } from "react";
import isArray from "lodash/isArray";
import isUndefined from "lodash/isUndefined";

import { Experiment } from "@/types/datasets";
import { getFeedbackScoreValue } from "@/lib/feedback-scores";
import { calculatePercentageChange } from "@/lib/utils";

type ScoreData = {
  score: number;
  percentage?: number;
};

type UseOptimizationScoresResult = {
  scoreMap: Record<string, ScoreData>;
  baseScore: number;
  bestExperiment?: Experiment;
};

export const useOptimizationScores = (
  experiments: Experiment[],
  objectiveName: string | undefined,
): UseOptimizationScoresResult => {
  return useMemo(() => {
    const retVal: UseOptimizationScoresResult = {
      scoreMap: {},
      baseScore: 0,
    };
    let maxScoreValue: number;

    const sortedRows = experiments
      .slice()
      .sort((e1, e2) => e1.created_at.localeCompare(e2.created_at));

    if (
      !objectiveName ||
      !experiments.length ||
      !isArray(sortedRows?.[0]?.feedback_scores)
    )
      return retVal;

    retVal.baseScore =
      getFeedbackScoreValue(sortedRows[0].feedback_scores, objectiveName) ?? 0;

    if (retVal.baseScore === 0) return retVal;

    experiments.forEach((e) => {
      const score = getFeedbackScoreValue(
        e.feedback_scores ?? [],
        objectiveName,
      );

      if (!isUndefined(score)) {
        if (isUndefined(maxScoreValue) || score > maxScoreValue) {
          maxScoreValue = score;
          retVal.bestExperiment = e;
        }

        retVal.scoreMap[e.id] = {
          score,
          percentage: calculatePercentageChange(retVal.baseScore, score),
        };
      }
    });

    return retVal;
  }, [experiments, objectiveName]);
};
