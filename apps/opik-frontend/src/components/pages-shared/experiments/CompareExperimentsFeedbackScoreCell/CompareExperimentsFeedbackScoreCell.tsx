import React from "react";
import { CellContext } from "@tanstack/react-table";

import { ExperimentItem, ExperimentsCompare } from "@/types/datasets";
import VerticallySplitCellWrapper, {
  CustomMeta,
} from "@/components/pages-shared/experiments/VerticallySplitCellWrapper/VerticallySplitCellWrapper";
import { MessageSquareMore } from "lucide-react";
import FeedbackScoreReasonTooltip from "@/components/shared/FeedbackScoreTag/FeedbackScoreReasonTooltip";
import {
  extractReasonsFromValueByAuthor,
  getIsMultiValueFeedbackScore,
} from "@/lib/feedback-scores";
import FeedbackScoreCellValue from "@/components/shared/DataTableCells/FeedbackScoreCellValue";
import { FeedbackScoreCustomMeta } from "@/types/feedback-scores";

const CompareExperimentsFeedbackScoreCell: React.FC<
  CellContext<ExperimentsCompare, unknown>
> = (context) => {
  const experimentCompare = context.row.original;
  const { custom } = context.column.columnDef.meta ?? {};
  const { feedbackKey, colorMap } = (custom ?? {}) as CustomMeta &
    FeedbackScoreCustomMeta;

  const renderContent = (item: ExperimentItem | undefined) => {
    const feedbackScore = item?.feedback_scores?.find(
      (f) => f.name === feedbackKey,
    );

    if (!feedbackScore) {
      return "-";
    }

    let reasons = feedbackScore.reason
      ? [
          {
            reason: feedbackScore.reason,
            author: feedbackScore.last_updated_by,
            lastUpdatedAt: feedbackScore.last_updated_at,
          },
        ]
      : [];

    if (getIsMultiValueFeedbackScore(feedbackScore.value_by_author)) {
      reasons = extractReasonsFromValueByAuthor(feedbackScore.value_by_author);
    }

    const color = feedbackKey && colorMap ? colorMap[feedbackKey] : undefined;

    return (
      <div className="flex h-4 w-full items-center justify-end gap-1">
        <FeedbackScoreCellValue feedbackScore={feedbackScore} color={color} />
        {reasons.length > 0 && (
          <FeedbackScoreReasonTooltip reasons={reasons}>
            <MessageSquareMore className="size-3.5 shrink-0 text-light-slate" />
          </FeedbackScoreReasonTooltip>
        )}
      </div>
    );
  };

  return (
    <VerticallySplitCellWrapper
      renderContent={renderContent}
      experimentCompare={experimentCompare}
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      rowId={context.row.id}
    />
  );
};

export default CompareExperimentsFeedbackScoreCell;
