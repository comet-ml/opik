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

    // If no experiments, return empty result
    if (!sortedRows.length) {
      return retVal;
    }

    // If we have experiments but no scores yet, use the first (or most recent) experiment as best
    if (!objectiveName || !isArray(sortedRows?.[0]?.feedback_scores)) {
      // Use the most recent experiment as the "best" when no scores exist
      retVal.bestExperiment = sortedRows[sortedRows.length - 1];
      return retVal;
    }

    retVal.baseScore =
      getFeedbackScoreValue(sortedRows[0].feedback_scores, objectiveName) ?? 0;

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

    // If no experiment had a valid score, fallback to the most recent experiment
    if (!retVal.bestExperiment) {
      retVal.bestExperiment = sortedRows[sortedRows.length - 1];
    }

    return retVal;
  }, [experiments, objectiveName]);
};
