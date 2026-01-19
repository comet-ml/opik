import {
  COLUMN_EXPERIMENT_SCORES_ID,
  COLUMN_FEEDBACK_SCORES_ID,
  SCORE_TYPE_EXPERIMENT,
  SCORE_TYPE_FEEDBACK,
  ScoreType,
  AggregatedFeedbackScore,
} from "@/types/shared";

// Build score column ID from score name and type
export const buildScoreColumnId = (
  scoreName: string,
  scoreType: ScoreType = SCORE_TYPE_FEEDBACK,
): string => {
  const prefix =
    scoreType === SCORE_TYPE_EXPERIMENT
      ? COLUMN_EXPERIMENT_SCORES_ID
      : COLUMN_FEEDBACK_SCORES_ID;
  return `${prefix}.${scoreName}`;
};

// Build display label with "(avg)" for feedback scores
export const buildScoreLabel = (
  scoreName: string,
  scoreType: ScoreType = SCORE_TYPE_FEEDBACK,
): string => {
  return scoreType === SCORE_TYPE_EXPERIMENT ? scoreName : `${scoreName} (avg)`;
};

// Parse score column ID to extract score name and type
export type ParsedScoreColumn = {
  scoreName: string;
  scoreType: ScoreType;
};

export const parseScoreColumnId = (
  columnId: string,
): ParsedScoreColumn | null => {
  if (columnId.startsWith(`${COLUMN_EXPERIMENT_SCORES_ID}.`)) {
    return {
      scoreName: columnId.substring(COLUMN_EXPERIMENT_SCORES_ID.length + 1),
      scoreType: SCORE_TYPE_EXPERIMENT,
    };
  }
  if (columnId.startsWith(`${COLUMN_FEEDBACK_SCORES_ID}.`)) {
    return {
      scoreName: columnId.substring(COLUMN_FEEDBACK_SCORES_ID.length + 1),
      scoreType: SCORE_TYPE_FEEDBACK,
    };
  }
  return null;
};

// Type for rows that have score properties
export type RowWithScores = {
  experiment_scores?: AggregatedFeedbackScore[];
  feedback_scores?: AggregatedFeedbackScore[];
};

// Get score from row by column ID - automatically parses ID and retrieves correct score
export const getExperimentScore = (
  columnId: string,
  row: RowWithScores,
): AggregatedFeedbackScore | undefined => {
  const parsed = parseScoreColumnId(columnId);
  if (!parsed) return undefined;

  const scores =
    parsed.scoreType === SCORE_TYPE_EXPERIMENT
      ? row.experiment_scores
      : row.feedback_scores;

  return scores?.find((s) => s.name === parsed.scoreName);
};
