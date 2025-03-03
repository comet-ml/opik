import { CellContext } from "@tanstack/react-table";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import { ROW_HEIGHT } from "@/types/shared";
import { TraceFeedbackScore } from "@/types/traces";
import FeedbackScoreReasonTooltip from "../FeedbackScoreTag/FeedbackScoreReasonTooltip";

const FeedbackScoreReasonCell = (
  context: CellContext<TraceFeedbackScore, string>,
) => {
  const value = context.getValue();
  const feedbackScore = context.row.original;

  const rowHeight =
    context.column.columnDef.meta?.overrideRowHeight ??
    context.table.options.meta?.rowHeight ??
    ROW_HEIGHT.small;

  const isSmall = rowHeight === ROW_HEIGHT.small;

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      <FeedbackScoreReasonTooltip
        reason={feedbackScore.reason}
        lastUpdatedAt={feedbackScore.last_updated_at}
        lastUpdatedBy={feedbackScore.last_updated_by}
      >
        {isSmall ? (
          <span className="truncate">{value}</span>
        ) : (
          <div className="size-full overflow-y-auto">{value}</div>
        )}
      </FeedbackScoreReasonTooltip>
    </CellWrapper>
  );
};

export default FeedbackScoreReasonCell;
