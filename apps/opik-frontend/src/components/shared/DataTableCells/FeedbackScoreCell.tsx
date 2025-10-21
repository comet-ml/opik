import React, { useMemo, useCallback } from "react";
import { CellContext } from "@tanstack/react-table";
import { MessageSquareMore } from "lucide-react";
import isNumber from "lodash/isNumber";
import isFunction from "lodash/isFunction";

import { cn, formatNumericData } from "@/lib/utils";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import FeedbackScoreReasonTooltip from "../FeedbackScoreTag/FeedbackScoreReasonTooltip";
import { TraceFeedbackScore, Thread } from "@/types/traces";
import {
  extractReasonsFromValueByAuthor,
  getIsMultiValueFeedbackScore,
} from "@/lib/feedback-scores";
import FeedbackScoreCellValue from "./FeedbackScoreCellValue";
import useTraceFeedbackScoreSetMutation from "@/api/traces/useTraceFeedbackScoreSetMutation";
import { BaseTraceData } from "@/types/traces";
import { useLoggedInUserNameOrOpenSourceDefaultUser } from "@/store/AppStore";
import useTraceFeedbackScoreDeleteMutation from "@/api/traces/useTraceFeedbackScoreDeleteMutation";
import useThreadFeedbackScoreSetMutation from "@/api/traces/useThreadFeedbackScoreSetMutation";
import useThreadFeedbackScoreDeleteMutation from "@/api/traces/useThreadFeedbackScoreDeleteMutation";
import { USER_FEEDBACK_NAME } from "@/constants/shared";

const FeedbackScoreCell = (context: CellContext<unknown, unknown>) => {
  const feedbackScore = context.getValue() as TraceFeedbackScore | undefined;
  const reason = feedbackScore?.reason;
  const row = context.row.original as BaseTraceData | Thread;

  const currentUserName = useLoggedInUserNameOrOpenSourceDefaultUser();

  const { mutate: deleteTraceFeedbackScore } =
    useTraceFeedbackScoreDeleteMutation();
  const { mutate: setTraceFeedbackScore } = useTraceFeedbackScoreSetMutation();

  const { mutate: deleteThreadFeedbackScore } =
    useThreadFeedbackScoreDeleteMutation();
  const { mutate: setThreadFeedbackScore } =
    useThreadFeedbackScoreSetMutation();

  // Get projectId and projectName from table meta
  const projectId = (
    context.table.options.meta as { projectId?: string } | undefined
  )?.projectId;
  const projectName = (
    context.table.options.meta as { projectName?: string } | undefined
  )?.projectName;

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

  const handleValueChange = useCallback(
    (categoryName: string, value: number) => {
      const isSameValue =
        feedbackScore?.value_by_author?.[currentUserName]?.value === value;

      if ("thread_model_id" in row) {
        // Handle Thread feedback score
        if (!projectId || !projectName) {
          console.error(
            "projectId and projectName are required for thread feedback scores",
          );
          return;
        }

        if (!isSameValue) {
          setThreadFeedbackScore({
            threadId: row.id,
            projectId,
            projectName,
            scores: [
              {
                name: USER_FEEDBACK_NAME,
                categoryName,
                value,
              },
            ],
          });
        } else {
          deleteThreadFeedbackScore({
            threadId: row.id,
            projectId,
            projectName,
            names: [USER_FEEDBACK_NAME],
          });
        }
      } else {
        // Handle Trace/Span feedback score
        if (!isSameValue) {
          setTraceFeedbackScore({
            traceId: row.id,
            name: USER_FEEDBACK_NAME,
            categoryName,
            value,
          });
        } else {
          deleteTraceFeedbackScore({
            traceId: row.id,
            name: USER_FEEDBACK_NAME,
          });
        }
      }
    },
    [
      currentUserName,
      feedbackScore,
      row,
      projectId,
      projectName,
      setThreadFeedbackScore,
      deleteThreadFeedbackScore,
      setTraceFeedbackScore,
      deleteTraceFeedbackScore,
    ],
  );

  const enableUserFeedbackEditing =
    context.table.options.meta?.enableUserFeedbackEditing ?? false;
  const isUserFeedbackColumn =
    enableUserFeedbackEditing &&
    context.column.id === "feedback_scores_User feedback";

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className={cn("gap-1", isUserFeedbackColumn && "group")}
    >
      <FeedbackScoreCellValue
        feedbackScore={feedbackScore}
        isUserFeedbackColumn={isUserFeedbackColumn}
        onValueChange={handleValueChange}
      />

      {reasons.length > 0 && (
        <FeedbackScoreReasonTooltip reasons={reasons}>
          <div className="flex h-[20px] items-center">
            <MessageSquareMore className="mt-0.5 size-3.5 shrink-0 text-light-slate" />
          </div>
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
  const { accessorFn, dataFormatter = formatNumericData } = (custom ??
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
      <span className="truncate text-light-slate">{value}</span>
    </CellWrapper>
  );
};

FeedbackScoreCell.Aggregation = FeedbackScoreAggregationCell;

export default FeedbackScoreCell;
