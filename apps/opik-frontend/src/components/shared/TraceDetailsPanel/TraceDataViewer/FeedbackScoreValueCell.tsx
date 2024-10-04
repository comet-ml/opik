import { CellContext } from "@tanstack/react-table";
import React from "react";
import { TraceFeedbackScore } from "@/types/traces";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import { MessageSquareMore } from "lucide-react";

const FeedbackScoreValueCell = (
  context: CellContext<TraceFeedbackScore, string>,
) => {
  const feedbackScore = context.row.original;
  const value = context.getValue();
  const Reason = feedbackScore.reason ? (
    <TooltipWrapper content={feedbackScore.reason} delayDuration={100}>
      <MessageSquareMore className="size-4 text-light-slate" />
    </TooltipWrapper>
  ) : null;
  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="gap-1.5"
    >
      <span className="truncate">{value}</span> {Reason && Reason}
    </CellWrapper>
  );
};

export default FeedbackScoreValueCell;
