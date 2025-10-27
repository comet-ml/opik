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
import useFeedbackScoreInlineEdit from "@/hooks/useFeedbackScoreInlineEdit";
import { cn } from "@/lib/utils";
import FeedbackScoreEditDropdown from "@/components/shared/DataTableCells/FeedbackScoreEditDropdown";

const CompareExperimentsFeedbackScoreCell: React.FC<
  CellContext<ExperimentsCompare, unknown>
> = (context) => {
  const experimentCompare = context.row.original;
  const { custom } = context.column.columnDef.meta ?? {};
  const { feedbackKey, colorMap } = (custom ?? {}) as CustomMeta &
    FeedbackScoreCustomMeta;
  const experimentItem = experimentCompare.experiment_items[0];

  const feedbackScore = experimentItem?.feedback_scores?.find(
    (f) => f.name === feedbackKey,
  );

  const traceId = experimentItem?.trace_id;

  const { handleValueChange } = useFeedbackScoreInlineEdit({
    id: traceId!, // TODO: handle case where traceId is not found
    feedbackScore,
  });

  const enableUserFeedbackEditing =
    experimentCompare.experiment_items.length === 1 &&
    (context.table.options.meta?.enableUserFeedbackEditing ?? false);
  const isUserFeedbackColumn =
    enableUserFeedbackEditing &&
    context.column.id === "feedback_scores_User feedback";

  const renderContent = (item: ExperimentItem | undefined) => {
    const feedbackScore = item?.feedback_scores?.find(
      (f) => f.name === feedbackKey,
    );

    if (!feedbackScore) {
      return (
        <div className="flex items-center gap-1">
          {isUserFeedbackColumn && (
            <FeedbackScoreEditDropdown
              feedbackScore={feedbackScore}
              onValueChange={handleValueChange}
            />
          )}
          <span>-</span>
        </div>
      );
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
      <div
        className={cn(
          "flex h-4 w-full items-center justify-end gap-1",
          isUserFeedbackColumn && "group",
        )}
      >
        <FeedbackScoreCellValue
          feedbackScore={feedbackScore}
          color={color}
          isUserFeedbackColumn={isUserFeedbackColumn}
          onValueChange={handleValueChange}
        />
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
