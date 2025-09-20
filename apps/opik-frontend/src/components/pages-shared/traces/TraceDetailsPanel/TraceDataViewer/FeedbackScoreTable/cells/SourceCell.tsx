import { CellContext } from "@tanstack/react-table";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import { ExpandingFeedbackScoreRow } from "../types";
import { FEEDBACK_SCORE_SOURCE_MAP } from "@/lib/feedback-scores";
import { getIsParentFeedbackScoreRow } from "../utils";
import { cn } from "@/lib/utils";

const SourceCell = (
  context: CellContext<ExpandingFeedbackScoreRow, string>,
) => {
  const row = context.row.original;
  const sourceText = FEEDBACK_SCORE_SOURCE_MAP[row.source];

  const isParentFeedbackScoreRow = getIsParentFeedbackScoreRow(row);

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      <span
        className={cn("truncate", {
          "text-light-slate": isParentFeedbackScoreRow,
        })}
      >
        {sourceText}
      </span>
    </CellWrapper>
  );
};

export default SourceCell;
