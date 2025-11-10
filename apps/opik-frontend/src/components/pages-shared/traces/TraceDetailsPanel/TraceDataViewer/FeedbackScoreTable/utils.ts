import {
  FeedbackScoreValueByAuthorMap,
  TraceFeedbackScore,
} from "@/types/traces";
import { ExpandingFeedbackScoreRow } from "./types";
import { getIsMultiValueFeedbackScore } from "@/lib/feedback-scores";
import { PARENT_ROW_ID_PREFIX } from "./constants";

export const mapFeedbackScoresToRowsWithExpanded = (
  feedbackScores: TraceFeedbackScore[],
): ExpandingFeedbackScoreRow[] => {
  const rows: ExpandingFeedbackScoreRow[] = [];

  feedbackScores.forEach((feedbackScore) => {
    if (getIsMultiValueFeedbackScore(feedbackScore.value_by_author)) {
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
      author: feedbackScore.created_by,
      ...feedbackScore,
    });
  });

  return rows;
};

export const getIsParentFeedbackScoreRow = (
  row: ExpandingFeedbackScoreRow,
): row is ExpandingFeedbackScoreRow & {
  value_by_author: FeedbackScoreValueByAuthorMap;
} => {
  return getIsMultiValueFeedbackScore(row.value_by_author) && !row.author;
};
