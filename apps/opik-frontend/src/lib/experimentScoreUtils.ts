import get from "lodash/get";
import { formatNumericData } from "@/lib/utils";
import {
  AggregatedFeedbackScore,
  SCORE_TYPE_FEEDBACK,
  SCORE_TYPE_EXPERIMENT,
} from "@/types/shared";

/**
 * Formatted score with string value for display purposes.
 */
export interface FormattedScore {
  name: string;
  value: string | number;
}

/**
 * Transform and combine feedback scores and experiment scores for display.
 * Adds "(avg)" suffix to feedback score names and formats numeric values.
 *
 * @param row The experiment row data containing feedback_scores and experiment_scores
 * @returns Combined array of formatted scores
 */
export const transformExperimentScores = (
  row:
    | {
        feedback_scores?: AggregatedFeedbackScore[];
        experiment_scores?: AggregatedFeedbackScore[];
      }
    | Record<string, unknown>,
): FormattedScore[] => {
  const formatScores = (key: string, addAvgSuffix: boolean): FormattedScore[] =>
    (get(row, key, []) as AggregatedFeedbackScore[]).map((score) => ({
      ...score,
      name: addAvgSuffix ? `${score.name} (avg)` : score.name,
      value: formatNumericData(score.value),
    }));

  return [
    ...formatScores(SCORE_TYPE_FEEDBACK, true),
    ...formatScores(SCORE_TYPE_EXPERIMENT, false),
  ];
};
