import { CellContext } from "@tanstack/react-table";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import { ExpandingFeedbackScoreRow } from "../types";
import FeedbackScoreReasonTooltip from "@/components/shared/FeedbackScoreTag/FeedbackScoreReasonTooltip";
import { cn } from "@/lib/utils";
import { getIsParentFeedbackScoreRow } from "../utils";
import {
  extractReasonsFromValueByAuthor,
  isValidReason,
} from "@/lib/feedback-scores";

const ReasonCell = (
  context: CellContext<ExpandingFeedbackScoreRow, string>,
) => {
  const value = context.getValue();
  const rowData = context.row.original;

  const isParentFeedbackScoreRow = getIsParentFeedbackScoreRow(rowData);

  const reasons = isParentFeedbackScoreRow
    ? extractReasonsFromValueByAuthor(rowData.value_by_author)
    : [
        {
          reason: value,
          author: rowData.author ?? rowData.last_updated_by,
          lastUpdatedAt: rowData.last_updated_at,
        },
      ];

  // Filter out empty reasons and "<no reason>" placeholders
  const filteredReasons = reasons.filter((r) => isValidReason(r.reason));

  const reasonsList = filteredReasons.map((reason) => reason.reason).join(", ");

  if (filteredReasons.length === 0) {
    return null;
  }

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      <FeedbackScoreReasonTooltip reasons={reasons}>
        <span
          className={cn("truncate", {
            "text-light-slate": isParentFeedbackScoreRow,
          })}
        >
          {reasonsList}
        </span>
      </FeedbackScoreReasonTooltip>
    </CellWrapper>
  );
};

export default ReasonCell;
