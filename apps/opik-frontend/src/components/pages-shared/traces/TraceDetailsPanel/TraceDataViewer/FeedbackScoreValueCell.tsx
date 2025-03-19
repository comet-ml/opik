import { CellContext } from "@tanstack/react-table";
import { TraceFeedbackScore } from "@/types/traces";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import { categoryOptionLabelRenderer } from "@/lib/feedback-scores";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";

const FeedbackScoreValueCell = (
  context: CellContext<TraceFeedbackScore, string>,
) => {
  const feedbackScore = context.row.original;
  const value = context.getValue();

  const computedValue = feedbackScore.category_name
    ? categoryOptionLabelRenderer(feedbackScore.category_name, value)
    : value;
  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="gap-1.5"
    >
      <TooltipWrapper content={computedValue} stopClickPropagation>
        <span className="truncate direction-alternate">{computedValue}</span>
      </TooltipWrapper>
    </CellWrapper>
  );
};

export default FeedbackScoreValueCell;
