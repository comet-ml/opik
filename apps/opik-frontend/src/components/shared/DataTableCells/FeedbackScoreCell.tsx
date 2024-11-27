import React from "react";
import { CellContext } from "@tanstack/react-table";

import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { TraceFeedbackScore } from "@/types/traces";
import { MessageSquareMore } from "lucide-react";

const FeedbackScoreCell = (context: CellContext<unknown, unknown>) => {
  const feedbackScore = context.getValue() as TraceFeedbackScore | undefined;

  const value = feedbackScore ? feedbackScore.value : "-";
  const reason = feedbackScore?.reason;

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="gap-1"
    >
      <div className="truncate">{value}</div>
      {reason && (
        <TooltipWrapper content={reason} delayDuration={100}>
          <MessageSquareMore className="size-3.5 shrink-0 text-light-slate" />
        </TooltipWrapper>
      )}
    </CellWrapper>
  );
};

export default FeedbackScoreCell;
