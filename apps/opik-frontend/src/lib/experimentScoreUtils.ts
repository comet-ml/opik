import get from "lodash/get";
import { formatNumericData } from "@/lib/utils";
import { AggregatedFeedbackScore } from "@/types/shared";

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
  row: Record<string, unknown>,
): FormattedScore[] => {
  const feedbackScores = (
    get(row, "feedback_scores", []) as AggregatedFeedbackScore[]
  ).map((score) => ({
    ...score,
    name: `${score.name} (avg)`,
    value: formatNumericData(score.value),
  }));

  const experimentScores = (
    get(row, "experiment_scores", []) as AggregatedFeedbackScore[]
  ).map((score) => ({
    ...score,
    value: formatNumericData(score.value),
  }));

  return [...feedbackScores, ...experimentScores];
};
