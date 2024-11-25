import { CellContext } from "@tanstack/react-table";
import sortBy from "lodash/sortBy";

import { cn } from "@/lib/utils";
import { ROW_HEIGHT } from "@/types/shared";
import { TraceFeedbackScore } from "@/types/traces";
import FeedbackScoreTag from "../FeedbackScoreTag/FeedbackScoreTag";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";

const FeedbackScoresCell = (context: CellContext<unknown, unknown>) => {
  const feedbackScores = context.getValue() as TraceFeedbackScore[];

  if (!Array.isArray(feedbackScores) || feedbackScores.length === 0) {
    return null;
  }

  const rowHeight = context.table.options.meta?.rowHeight ?? ROW_HEIGHT.small;

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="py-0"
    >
      <div
        className={cn(
          "flex max-h-full flex-row gap-2",
          rowHeight === ROW_HEIGHT.small
            ? "overflow-x-auto"
            : "flex-wrap overflow-auto",
        )}
      >
        {sortBy(feedbackScores, "name").map((feedbackScore) => {
          return (
            <FeedbackScoreTag
              key={feedbackScore.name + feedbackScore.value}
              label={feedbackScore.name}
              value={feedbackScore.value}
              reason={feedbackScore.reason}
            />
          );
        })}
      </div>
    </CellWrapper>
  );
};

export default FeedbackScoresCell;
