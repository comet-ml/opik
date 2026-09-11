import { categoryOptionLabelRenderer } from "@/lib/feedback-scores";
import { FeedbackScoreValueByAuthorMap } from "@/types/traces";

export type UserEntry = {
  mapKey: string;
  entry: FeedbackScoreValueByAuthorMap[string];
};

export const getIsCategoricFeedbackScore = (categoryNames = "") => {
  return !!categoryNames.split(",").filter((v) => !!v?.trim()).length;
};

export const getCategoricFeedbackScoreValuesMap = (
  valueByAuthor: FeedbackScoreValueByAuthorMap,
) => {
  const scoreMap: Map<string, { users: UserEntry[]; value: string }> =
    new Map();

  Object.entries(valueByAuthor).forEach(([mapKey, score]) => {
    if (!score.category_name) return;

    if (!scoreMap.has(score.category_name)) {
      scoreMap.set(score.category_name, {
        users: [],
        value: categoryOptionLabelRenderer(score.category_name, score.value),
      });
    }
    scoreMap.get(score.category_name)?.users.push({ mapKey, entry: score });
  });

  return scoreMap;
};
