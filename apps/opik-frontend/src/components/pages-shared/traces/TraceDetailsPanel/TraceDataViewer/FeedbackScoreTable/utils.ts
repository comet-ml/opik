import { TraceFeedbackScore } from "@/types/traces";
import { ExpandingFeedbackScoreRow } from "./types";
import { isMultiValueFeedbackScore } from "@/lib/feedback-scores";
import { PARENT_ROW_ID_PREFIX } from "./constants";

export const mapFeedbackScoresToRowsWithExpanded = (
  feedbackScores: TraceFeedbackScore[],
): ExpandingFeedbackScoreRow[] => {
  const rows: ExpandingFeedbackScoreRow[] = [];

  feedbackScores.forEach((feedbackScore) => {
    if (isMultiValueFeedbackScore(feedbackScore)) {
      const parentId = `${PARENT_ROW_ID_PREFIX}${feedbackScore.name}`;
      const parentRow: ExpandingFeedbackScoreRow = {
        id: parentId,
        ...feedbackScore,
        subRows: [],
      };

      rows.push(parentRow);

      parentRow.subRows = Object.entries(
        feedbackScore.value_by_author ?? {},
      ).map(([author, score]) => ({
        id: `${parentId}${author}`,
        ...score,
        author,
        name: feedbackScore.name,
        value_by_author: feedbackScore.value_by_author,
      }));

      return;
    }

    rows.push({
      id: feedbackScore.name,
      ...feedbackScore,
    });
  });

  return rows;
};
