import React, { useMemo } from "react";
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
import { FeedbackScoreValueByAuthorMap } from "@/types/traces";

type ValueCellProps = CellContext<ExpandingFeedbackScoreRow, string>;

const formatCategoricScoreValue = (
  valuesByAuthor: FeedbackScoreValueByAuthorMap,
): string => {
  const scoreValuesMap = getCategoricFeedbackScoreValuesMap(valuesByAuthor);
  return Array.from(scoreValuesMap.values())
    .map(({ users, value }) => `${users.length}x ${value}`)
    .join(", ");
};

const renderParentValue = (displayText: string): React.ReactElement => (
  <div className="truncate text-light-slate">{displayText}</div>
);

const ValueCell: React.FC<ValueCellProps> = (context) => {
  const rowData = context.row.original;
  const value = context.getValue();

  const isParentRow = getIsParentFeedbackScoreRow(rowData);
  const isCategoricScore = getIsCategoricFeedbackScore(rowData.category_name);

  const cellContent = useMemo((): string | React.ReactElement => {
    if (isParentRow && isCategoricScore) {
      const displayText = formatCategoricScoreValue(
        rowData.value_by_author ?? {},
      );
      return renderParentValue(displayText);
    }

    if (isParentRow) {
      return renderParentValue(value);
    }

    if (rowData.category_name) {
      return categoryOptionLabelRenderer(rowData.category_name, value);
    }

    return value;
  }, [
    isParentRow,
    isCategoricScore,
    rowData.value_by_author,
    rowData.category_name,
    value,
  ]);

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="gap-1.5"
    >
      <TooltipWrapper content={cellContent} stopClickPropagation>
        <span className="truncate direction-alternate">{cellContent}</span>
      </TooltipWrapper>
    </CellWrapper>
  );
};

export default ValueCell;
