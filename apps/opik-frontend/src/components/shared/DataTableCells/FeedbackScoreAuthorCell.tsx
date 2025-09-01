import { CellContext } from "@tanstack/react-table";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import { ROW_HEIGHT } from "@/types/shared";
import { TraceFeedbackScore } from "@/types/traces";
import { isMultiValueFeedbackScore } from "@/lib/feedback-scores";

const FeedbackScoreAuthorCell = (
  context: CellContext<TraceFeedbackScore, string>,
) => {
  const value = context.getValue();
  const feedbackScore = context.row.original;

  const rowHeight =
    context.column.columnDef.meta?.overrideRowHeight ??
    context.table.options.meta?.rowHeight ??
    ROW_HEIGHT.small;

  const isSmall = rowHeight === ROW_HEIGHT.small;

  const isMultiValueScore = isMultiValueFeedbackScore(feedbackScore);

  const authors = isMultiValueScore
    ? Object.keys(feedbackScore.value_by_author)
    : [value];

  const authorsList = authors.join(", ");

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      {isSmall ? (
        <span className="truncate">{authorsList}</span>
      ) : (
        <div className="size-full overflow-y-auto">{authorsList}</div>
      )}
    </CellWrapper>
  );
};

export default FeedbackScoreAuthorCell;
