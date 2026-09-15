import React from "react";
import { CellContext, TableMeta } from "@tanstack/react-table";

import { ExperimentItem, ExperimentsCompare } from "@/types/datasets";
import VerticallySplitCellWrapper, {
  CustomMeta,
} from "@/shared/DataTableCells/VerticallySplitCellWrapper";
import { MessageSquareMore } from "lucide-react";
import { isAggregatedScore, getTrialAvgTooltip } from "@/lib/trials";
import FeedbackScoreReasonTooltip from "@/shared/FeedbackScoreTag/FeedbackScoreReasonTooltip";
import {
  extractReasonsFromValueByAuthor,
  getIsMultiValueFeedbackScore,
} from "@/lib/feedback-scores";
import FeedbackScoreCellValue from "@/shared/DataTableCells/FeedbackScoreCellValue";
import { FeedbackScoreCustomMeta } from "@/types/feedback-scores";
import useFeedbackScoreInlineEdit from "@/hooks/useFeedbackScoreInlineEdit";
import { cn } from "@/lib/utils";
import FeedbackScoreEditDropdown from "@/shared/DataTableCells/FeedbackScoreEditDropdown";
import { ROW_HEIGHT } from "@/types/shared";
import { USER_FEEDBACK_NAME } from "@/constants/shared";

type CompareExperimentsFeedbackScoreCellProps = {
  context: CellContext<ExperimentsCompare, unknown>;
  editable?: boolean;
  onValueChange?: (name: string, value: number) => void;
};

const CompareExperimentsFeedbackScoreCellContent = ({
  context,
  editable = false,
  onValueChange,
}: CompareExperimentsFeedbackScoreCellProps) => {
  const experimentCompare = context.row.original;
  const { custom } = context.column.columnDef.meta ?? {};
  const { scoreName, colorMap } = (custom ?? {}) as CustomMeta &
    FeedbackScoreCustomMeta;

  const { rowHeight = ROW_HEIGHT.small } = (context.table.options.meta ??
    {}) as TableMeta<ExperimentsCompare>;

  const canEditUserFeedback = editable && Boolean(onValueChange);
  const isCompact =
    rowHeight === ROW_HEIGHT.small || rowHeight === ROW_HEIGHT.medium;

  const renderContent = (item: ExperimentItem | undefined) => {
    const feedbackScore = item?.feedback_scores?.find(
      (f) => f.name === scoreName,
    );

    if (!feedbackScore) {
      return (
        <div className="flex items-center gap-1">
          {canEditUserFeedback && onValueChange && (
            <FeedbackScoreEditDropdown
              feedbackScore={feedbackScore}
              onValueChange={onValueChange}
              size={isCompact ? "sm" : "md"}
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

    const color = scoreName && colorMap ? colorMap[scoreName] : undefined;

    return (
      <div
        className={cn(
          "flex w-full justify-end gap-1",
          isCompact
            ? "h-4 items-center"
            : "flex-col items-end justify-start overflow-hidden",
          canEditUserFeedback && "group",
        )}
      >
        <FeedbackScoreCellValue
          feedbackScore={feedbackScore}
          color={color}
          isUserFeedbackColumn={canEditUserFeedback}
          onValueChange={onValueChange}
          size={isCompact ? "sm" : "md"}
          footer={
            isAggregatedScore(feedbackScore)
              ? getTrialAvgTooltip(
                  feedbackScore.trialValues.length,
                  feedbackScore.stdDev,
                )
              : undefined
          }
        />
        {reasons.length > 0 &&
          (isCompact ? (
            <FeedbackScoreReasonTooltip reasons={reasons}>
              <MessageSquareMore className="size-3.5 shrink-0 text-light-slate" />
            </FeedbackScoreReasonTooltip>
          ) : (
            <span className="w-full min-w-0 overflow-y-auto break-words text-xs text-muted-foreground">
              {reasons.map((r) => r.reason).join(", ")}
            </span>
          ))}
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

// Read-only by default: only the User feedback column needs the inline-edit
// hooks, and a table with many score columns would otherwise build them per cell
const CompareExperimentsFeedbackScoreCell: React.FC<
  CellContext<ExperimentsCompare, unknown>
> = (context) => (
  <CompareExperimentsFeedbackScoreCellContent context={context} />
);

const EditableCell = ({
  context,
  traceId,
}: {
  context: CellContext<ExperimentsCompare, unknown>;
  traceId: string;
}) => {
  const { custom } = context.column.columnDef.meta ?? {};
  const { scoreName } = (custom ?? {}) as CustomMeta & FeedbackScoreCustomMeta;
  const experimentItem = context.row.original.experiment_items[0];

  const { handleValueChange } = useFeedbackScoreInlineEdit({
    id: traceId,
    feedbackScore: experimentItem?.feedback_scores?.find(
      (f) => f.name === scoreName,
    ),
  });

  return (
    <CompareExperimentsFeedbackScoreCellContent
      context={context}
      editable
      onValueChange={handleValueChange}
    />
  );
};

// A split cell shows several experiments at once, and an experiment item can
// carry no trace, so in both cases there is nothing to write the score back to.
export const EditableCompareExperimentsFeedbackScoreCell: React.FC<
  CellContext<ExperimentsCompare, unknown>
> = (context) => {
  const experimentItems = context.row.original.experiment_items;
  const traceId =
    experimentItems.length === 1 ? experimentItems[0]?.trace_id : undefined;

  return traceId ? (
    <EditableCell context={context} traceId={traceId} />
  ) : (
    <CompareExperimentsFeedbackScoreCellContent context={context} />
  );
};

export const resolveCompareExperimentsFeedbackScoreCell = (
  scoreName: string,
) =>
  scoreName === USER_FEEDBACK_NAME
    ? EditableCompareExperimentsFeedbackScoreCell
    : CompareExperimentsFeedbackScoreCell;

export default CompareExperimentsFeedbackScoreCell;
