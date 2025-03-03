import React from "react";
import { CellContext } from "@tanstack/react-table";

import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import { TraceFeedbackScore } from "@/types/traces";
import { MessageSquareMore } from "lucide-react";
import FeedbackScoreReasonTooltip from "../FeedbackScoreTag/FeedbackScoreReasonTooltip";

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
        <FeedbackScoreReasonTooltip
          reason={reason}
          lastUpdatedAt={feedbackScore.last_updated_at}
          lastUpdatedBy={feedbackScore.last_updated_by}
        >
          <MessageSquareMore className="size-3.5 shrink-0 text-light-slate" />
        </FeedbackScoreReasonTooltip>
      )}
    </CellWrapper>
  );
};

export default FeedbackScoreCell;
