import React, { useMemo } from "react";
import { CellContext, TableMeta } from "@tanstack/react-table";
import { MessageSquareMore } from "lucide-react";
import isNumber from "lodash/isNumber";
import isFunction from "lodash/isFunction";

import { cn } from "@/lib/utils";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import FeedbackScoreReasonTooltip from "../FeedbackScoreTag/FeedbackScoreReasonTooltip";
import { TraceFeedbackScore, Thread, Span } from "@/types/traces";
import {
  extractReasonsFromValueByAuthor,
  getIsMultiValueFeedbackScore,
  formatScoreDisplay,
} from "@/lib/feedback-scores";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import FeedbackScoreCellValue from "./FeedbackScoreCellValue";
import { BaseTraceData } from "@/types/traces";
import useFeedbackScoreInlineEdit from "@/hooks/useFeedbackScoreInlineEdit";
import { isObjectSpan, isObjectThread } from "@/lib/traces";
import { ROW_HEIGHT } from "@/types/shared";

const FeedbackScoreCell = (context: CellContext<unknown, unknown>) => {
  const feedbackScore = context.getValue() as TraceFeedbackScore | undefined;
  const reason = feedbackScore?.reason;
  const row = context.row.original as BaseTraceData | Thread | Span;

  const {
    projectId,
    projectName,
    rowHeight = ROW_HEIGHT.small,
    enableUserFeedbackEditing = false,
  } = (context.table.options.meta ?? {}) as TableMeta<unknown>;

  const { handleValueChange } = useFeedbackScoreInlineEdit({
    id: row.id,
    isThread: isObjectThread(row),
    isSpan: isObjectSpan(row),
    feedbackScore,
    projectId,
    projectName,
  });

  const reasons = useMemo(() => {
    if (getIsMultiValueFeedbackScore(feedbackScore?.value_by_author)) {
      return extractReasonsFromValueByAuthor(feedbackScore?.value_by_author);
    }

    return reason
      ? [
          {
            reason,
            author: feedbackScore?.last_updated_by,
            lastUpdatedAt: feedbackScore?.last_updated_at,
          },
        ]
      : [];
  }, [
    feedbackScore?.value_by_author,
    reason,
    feedbackScore?.last_updated_by,
    feedbackScore?.last_updated_at,
  ]);

  // Editing is now enabled for all threads regardless of status
  const isEditingEnabled = enableUserFeedbackEditing;

  const isUserFeedbackColumn =
    isEditingEnabled && context.column.id === "feedback_scores_User feedback";
  const isSmall = rowHeight === ROW_HEIGHT.small;

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className={cn(
        "flex w-full justify-end gap-1",
        isSmall
          ? "h-4 items-center"
          : "flex-col items-end justify-start overflow-hidden",
        isUserFeedbackColumn && "group",
      )}
    >
      <FeedbackScoreCellValue
        feedbackScore={feedbackScore}
        isUserFeedbackColumn={isUserFeedbackColumn}
        onValueChange={handleValueChange}
      />

      {reasons.length > 0 && (
        <FeedbackScoreReasonTooltip reasons={reasons}>
          {!isSmall ? (
            <span
              className={cn(
                "break-words text-xs text-muted-foreground",
                rowHeight === ROW_HEIGHT.medium && "line-clamp-3",
                rowHeight === ROW_HEIGHT.large && "line-clamp-[16]",
              )}
            >
              {reasons.map((r) => r.reason).join(", ")}
            </span>
          ) : (
            <div className="flex h-[20px] items-center">
              <MessageSquareMore className="mt-0.5 size-3.5 shrink-0 text-light-slate" />
            </div>
          )}
        </FeedbackScoreReasonTooltip>
      )}
    </CellWrapper>
  );
};

type CustomMeta = {
  accessorFn?: string;
  dataFormatter?: (value: number) => string;
};

const FeedbackScoreAggregationCell = <TData,>(
  context: CellContext<TData, string>,
) => {
  const { custom } = context.column.columnDef.meta ?? {};
  const { accessorFn, dataFormatter = formatScoreDisplay } = (custom ??
    {}) as CustomMeta;

  const rowId = context.row.id;
  const { aggregationMap } = context.table.options.meta ?? {};

  const data = aggregationMap?.[rowId] ?? {};
  const rawValue = isFunction(accessorFn) ? accessorFn(data) : undefined;
  let value = "-";

  if (isNumber(rawValue)) {
    value = dataFormatter(rawValue);
  }

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      {isNumber(rawValue) ? (
        <TooltipWrapper content={String(rawValue)}>
          <span className="truncate text-light-slate">{value}</span>
        </TooltipWrapper>
      ) : (
        <span className="truncate text-light-slate">{value}</span>
      )}
    </CellWrapper>
  );
};

FeedbackScoreCell.Aggregation = FeedbackScoreAggregationCell;

export default FeedbackScoreCell;
