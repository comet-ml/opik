import { CellContext } from "@tanstack/react-table";
import { TraceFeedbackScore } from "@/types/traces";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import {
  categoryOptionLabelRenderer,
  isMultiValueFeedbackScore,
} from "@/lib/feedback-scores";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import {
  getCategoricFeedbackScoreValuesMap,
  getIsCategoricFeedbackScore,
} from "@/components/shared/FeedbackScoreTag/utils";

const FeedbackScoreValueCell = (
  context: CellContext<TraceFeedbackScore, string>,
) => {
  const feedbackScore = context.row.original;
  const value = context.getValue();

  const isMultiValueScore = isMultiValueFeedbackScore(feedbackScore);
  const isCategoricScore = getIsCategoricFeedbackScore(
    feedbackScore.category_name,
  );
  const renderValue = () => {
    if (isMultiValueScore) {
      if (isCategoricScore) {
        const scoreValuesMap = getCategoricFeedbackScoreValuesMap(
          feedbackScore.value_by_author,
        );

        return (
          <div className="flex gap-1">
            {Array.from(scoreValuesMap.entries()).map(
              ([category, { users, value }], index) => (
                <div key={category} className="flex items-center gap-1">
                  <span className="text-light-slate">{users.length}x</span>
                  <span className="truncate">
                    {value}
                    {index < scoreValuesMap.size - 1 && ","}
                  </span>
                </div>
              ),
            )}
          </div>
        );
      }

      return (
        <div className="flex items-center gap-1">
          <span className="text-light-slate">avg.</span>
          <span className="truncate">{value}</span>
        </div>
      );
    }

    if (feedbackScore.category_name) {
      return categoryOptionLabelRenderer(feedbackScore.category_name, value);
    }

    return value;
  };

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="gap-1.5"
    >
      <TooltipWrapper content={renderValue()} stopClickPropagation>
        <span className="truncate direction-alternate">{renderValue()}</span>
      </TooltipWrapper>
    </CellWrapper>
  );
};

export default FeedbackScoreValueCell;
