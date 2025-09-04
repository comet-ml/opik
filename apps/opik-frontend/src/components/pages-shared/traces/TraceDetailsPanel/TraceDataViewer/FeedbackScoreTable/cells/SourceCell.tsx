import { CellContext } from "@tanstack/react-table";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import { ExpandingFeedbackScoreRow } from "../types";
import {
  FEEDBACK_SCORE_SOURCE_MAP,
  isMultiValueFeedbackScore,
} from "@/lib/feedback-scores";

const SourceCell = (
  context: CellContext<ExpandingFeedbackScoreRow, string>,
) => {
  const row = context.row.original;
  const sourceText = FEEDBACK_SCORE_SOURCE_MAP[row.source];

  // Apply text-light-slate style if it's a multi-value score and has no author
  const isMultiValue = isMultiValueFeedbackScore(row) && !row.author;

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      <span className={`truncate ${isMultiValue ? "text-light-slate" : ""}`}>
        {sourceText}
      </span>
    </CellWrapper>
  );
};

export default SourceCell;
