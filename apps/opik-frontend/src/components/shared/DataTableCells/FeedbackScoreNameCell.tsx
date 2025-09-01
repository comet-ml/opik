import { CellContext } from "@tanstack/react-table";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import ColoredTagNew from "../ColoredTag/ColoredTagNew";
import { isMultiValueFeedbackScore } from "@/lib/feedback-scores";
import MultiValueFeedbackScoreName from "../FeedbackScoreTag/MultiValueFeedbackScoreName";

const FeedbackScoreNameCell = (context: CellContext<unknown, string>) => {
  const value = context.getValue();
  const isMultiValueScore = isMultiValueFeedbackScore(context.row.original);

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="gap-1.5"
    >
      {isMultiValueScore ? (
        <MultiValueFeedbackScoreName label={value} className="-ml-0.5 px-0" />
      ) : (
        <ColoredTagNew label={value} className="px-0" />
      )}
    </CellWrapper>
  );
};

export default FeedbackScoreNameCell;
