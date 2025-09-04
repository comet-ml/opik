import { CellContext } from "@tanstack/react-table";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import { ExpandingFeedbackScoreRow } from "../types";
import FeedbackScoreReasonTooltip from "@/components/shared/FeedbackScoreTag/FeedbackScoreReasonTooltip";
import { isMultiValueFeedbackScore } from "@/lib/feedback-scores";
import { cn } from "@/lib/utils";

const ReasonCell = (
  context: CellContext<ExpandingFeedbackScoreRow, string>,
) => {
  const value = context.getValue();
  const rowData = context.row.original;

  const isMultiValueScore =
    isMultiValueFeedbackScore(rowData) && !rowData.author;

  const reasons = isMultiValueScore
    ? Object.entries(rowData.value_by_author)
        .map(([author, { reason, last_updated_at, value }]) => ({
          author,
          reason: reason || "",
          lastUpdatedAt: last_updated_at,
          value,
        }))
        .filter(({ reason }) => reason)
    : [
        {
          reason: value,
          author: rowData.author ?? rowData.last_updated_by,
          lastUpdatedAt: rowData.last_updated_at,
        },
      ];

  const reasonsList = reasons.map((reason) => reason.reason).join(", ");

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      <FeedbackScoreReasonTooltip reasons={reasons}>
        <span
          className={cn("truncate", isMultiValueScore && "text-light-slate")}
        >
          {reasonsList}
        </span>
      </FeedbackScoreReasonTooltip>
    </CellWrapper>
  );
};

export default ReasonCell;
