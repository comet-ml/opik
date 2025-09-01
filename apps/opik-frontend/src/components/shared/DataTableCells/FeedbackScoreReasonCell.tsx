import { CellContext } from "@tanstack/react-table";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import { ROW_HEIGHT } from "@/types/shared";
import { TraceFeedbackScore } from "@/types/traces";
import FeedbackScoreReasonTooltip from "../FeedbackScoreTag/FeedbackScoreReasonTooltip";
import { isMultiValueFeedbackScore } from "@/lib/feedback-scores";

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

  const isMultiValueScore = isMultiValueFeedbackScore(feedbackScore);

  const reasons = isMultiValueScore
    ? Object.entries(feedbackScore.value_by_author)
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
          author: feedbackScore.last_updated_by,
          lastUpdatedAt: feedbackScore.last_updated_at,
        },
      ];

  const reasonsList = reasons.map((reason) => reason.reason).join(", ");

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      <FeedbackScoreReasonTooltip reasons={reasons}>
        {isSmall ? (
          <span className="truncate">{reasonsList}</span>
        ) : (
          <div className="size-full overflow-y-auto">{reasonsList}</div>
        )}
      </FeedbackScoreReasonTooltip>
    </CellWrapper>
  );
};

export default FeedbackScoreReasonCell;
