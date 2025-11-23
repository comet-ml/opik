import React, { useMemo } from "react";
import { CellContext } from "@tanstack/react-table";

import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import FeedbackScoreReasonTooltip from "../FeedbackScoreTag/FeedbackScoreReasonTooltip";
import { TraceFeedbackScore } from "@/types/traces";
import {
  extractReasonsFromValueByAuthor,
  getIsMultiValueFeedbackScore,
} from "@/lib/feedback-scores";

const FeedbackScoreReasonCell = (context: CellContext<unknown, unknown>) => {
  const feedbackScore = context.getValue() as TraceFeedbackScore | undefined;

  const reasons = useMemo(() => {
    if (!feedbackScore) return [];

    if (getIsMultiValueFeedbackScore(feedbackScore.value_by_author)) {
      return extractReasonsFromValueByAuthor(feedbackScore.value_by_author);
    }

    return feedbackScore.reason
      ? [
          {
            reason: feedbackScore.reason,
            author: feedbackScore.last_updated_by,
            lastUpdatedAt: feedbackScore.last_updated_at,
          },
        ]
      : [];
  }, [feedbackScore]);

  const reasonsList = reasons.map((r) => r.reason).join(", ");

  if (reasons.length === 0) {
    return (
      <CellWrapper
        metadata={context.column.columnDef.meta}
        tableMetadata={context.table.options.meta}
      >
        <span>-</span>
      </CellWrapper>
    );
  }

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      <FeedbackScoreReasonTooltip reasons={reasons}>
        <span className="truncate">{reasonsList}</span>
      </FeedbackScoreReasonTooltip>
    </CellWrapper>
  );
};

export default FeedbackScoreReasonCell;
