import React, { useMemo } from "react";
import { CellContext } from "@tanstack/react-table";
import { MessageSquareMore } from "lucide-react";
import isNumber from "lodash/isNumber";

import CellWrapper from "@/shared/DataTableCells/CellWrapper";
import FeedbackScoreReasonTooltip from "@/shared/FeedbackScoreTag/FeedbackScoreReasonTooltip";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { formatScoreDisplay, isValidReason } from "@/lib/feedback-scores";
import { TraceFeedbackScore } from "@/types/traces";

const TrialScoreCell = (context: CellContext<unknown, unknown>) => {
  const feedbackScore = context.getValue() as TraceFeedbackScore | undefined;
  const value = feedbackScore?.value;
  const reason = feedbackScore?.reason;

  const reasons = useMemo(
    () =>
      reason && isValidReason(reason)
        ? [
            {
              reason,
              author: feedbackScore?.last_updated_by,
              lastUpdatedAt: feedbackScore?.last_updated_at,
            },
          ]
        : [],
    [reason, feedbackScore?.last_updated_by, feedbackScore?.last_updated_at],
  );

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="flex w-full items-center justify-end gap-1"
    >
      {isNumber(value) ? (
        <TooltipWrapper content={String(value)}>
          <span>{formatScoreDisplay(value)}</span>
        </TooltipWrapper>
      ) : (
        "-"
      )}
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

export default TrialScoreCell;
