import { UpdateFeedbackScoreData } from "./types";
import { TraceFeedbackScore } from "@/types/traces";

export type BulkAnnotationPayload = {
  ids: string[];
  feedbackScore: UpdateFeedbackScoreData;
};

/**
 * Validates feedback score update data before applying bulk annotations across traces.
 */
export const validateBulkAnnotation = (
  data: UpdateFeedbackScoreData,
): { valid: boolean; error?: string } => {
  if (!data.name || data.name.trim() === "") {
    return { valid: false, error: "Feedback score name is required" };
  }

  if (
    typeof data.value !== "number" ||
    isNaN(data.value) ||
    !Number.isFinite(data.value)
  ) {
    return { valid: false, error: "Feedback score value must be a valid finite number" };
  }

  return { valid: true };
};

/**
 * Creates bulk annotation payload for multiple trace IDs.
 */
export const createBulkAnnotationPayload = (
  traceIds: string[],
  score: UpdateFeedbackScoreData,
): BulkAnnotationPayload => {
  return {
    ids: traceIds.filter((id) => Boolean(id && id.trim())),
    feedbackScore: {
      name: score.name.trim(),
      value: score.value,
      categoryName: score.categoryName?.trim() || undefined,
      reason: score.reason?.trim() || undefined,
    },
  };
};

/**
 * Optimistically merges a newly added or updated feedback score into a trace's existing feedback scores.
 */
export const mergeFeedbackScoreIntoList = (
  existingScores: TraceFeedbackScore[] = [],
  newScore: UpdateFeedbackScoreData,
  author: string = "default_user",
): TraceFeedbackScore[] => {
  const index = existingScores.findIndex((s) => s.name === newScore.name);
  const updatedEntry: TraceFeedbackScore = {
    name: newScore.name,
    value: newScore.value,
    category_name: newScore.categoryName,
    reason: newScore.reason,
    source: "ui",
    author,
  };

  if (index >= 0) {
    const updated = [...existingScores];
    updated[index] = { ...updated[index], ...updatedEntry };
    return updated;
  }

  return [...existingScores, updatedEntry];
};
