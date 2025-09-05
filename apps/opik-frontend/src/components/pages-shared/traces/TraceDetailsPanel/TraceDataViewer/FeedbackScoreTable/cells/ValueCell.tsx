import { CellContext } from "@tanstack/react-table";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import { categoryOptionLabelRenderer } from "@/lib/feedback-scores";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import {
  getCategoricFeedbackScoreValuesMap,
  getIsCategoricFeedbackScore,
} from "@/components/shared/FeedbackScoreTag/utils";
import { ExpandingFeedbackScoreRow } from "../types";
import { getIsParentFeedbackScoreRow } from "../utils";

const ValueCell = (context: CellContext<ExpandingFeedbackScoreRow, string>) => {
  const rowData = context.row.original;
  const value = context.getValue();

  const isParentFeedbackScoreRow = getIsParentFeedbackScoreRow(rowData);
  const isCategoricScore = getIsCategoricFeedbackScore(rowData.category_name);

  const renderValue = () => {
    if (isParentFeedbackScoreRow) {
      if (isCategoricScore) {
        const scoreValuesMap = getCategoricFeedbackScoreValuesMap(
          rowData.value_by_author ?? {},
        );

        return (
          <div className="flex gap-1 text-light-slate">
            {Array.from(scoreValuesMap.entries()).map(
              ([category, { users, value }], index) => (
                <div key={category} className="flex items-center gap-1">
                  <span>{users.length}x</span>
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
        <div className="flex items-center gap-1 text-light-slate">
          <span>avg.</span>
          <span className="truncate">{value}</span>
        </div>
      );
    }

    if (rowData.category_name) {
      return categoryOptionLabelRenderer(rowData.category_name, value);
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

export default ValueCell;
