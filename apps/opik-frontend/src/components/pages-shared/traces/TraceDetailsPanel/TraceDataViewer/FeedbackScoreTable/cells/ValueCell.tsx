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
  separator: string = ", ",
): string => {
  const scoreValuesMap = getCategoricFeedbackScoreValuesMap(valuesByAuthor);
  return Array.from(scoreValuesMap.values())
    .map(({ users, value }) => `${users.length}x ${value}`)
    .join(separator);
};

const formatValueWithCounts = (
  valuesByAuthor: FeedbackScoreValueByAuthorMap,
  averageValue: string | number,
  categoryName?: string,
): string => {
  // For categorical scores, reuse formatCategoricScoreValue with space separator
  // and include the average value in parentheses
  if (categoryName) {
    const formatted = formatCategoricScoreValue(valuesByAuthor, " ");
    return `${formatted} (${averageValue})`;
  }

  // For non-categorical scores (like emojis), count occurrences of each value
  const valueCounts = new Map<string | number, number>();
  
  Object.values(valuesByAuthor).forEach((score) => {
    // Use the raw value for counting (emojis, numbers, etc.)
    const displayValue = score.value;
    const currentCount = valueCounts.get(displayValue) || 0;
    valueCounts.set(displayValue, currentCount + 1);
  });

  // Format as "2x 👍 1x 👎" - sort by count descending, then by value
  // This matches the user's expected format: "2x 👍 1x 👎 (0.666666666)"
  const formattedParts = Array.from(valueCounts.entries())
    .sort((a, b) => {
      // Sort by count descending first
      if (b[1] !== a[1]) return b[1] - a[1];
      // Then by value for consistency
      return String(a[0]).localeCompare(String(b[0]));
    })
    .map(([value, count]) => `${count}x ${value}`)
    .join(" "); // Space-separated format

  return `${formattedParts} (${averageValue})`;
};

/**
 * Extracts the display value from a score entry.
 * Prefers category_name if available (contains display value like emoji), otherwise uses value.
 */
const getDisplayValue = (score: FeedbackScoreValueByAuthorMap[string]): string | number => {
  return score.category_name || score.value;
};

/**
 * Extracts the numeric value from a score entry.
 */
const getNumericValue = (score: FeedbackScoreValueByAuthorMap[string]): number => {
  if (typeof score.value === 'number') {
    return score.value;
  }
  const parsed = parseFloat(String(score.value));
  return isNaN(parsed) ? 0 : parsed;
};

/**
 * Counts occurrences of each display value and maps them to their numeric values.
 */
const countValuesByDisplay = (
  valuesByAuthor: FeedbackScoreValueByAuthorMap,
): {
  valueCounts: Map<string | number, number>;
  valueMap: Map<string | number, number>;
} => {
  const valueCounts = new Map<string | number, number>();
  const valueMap = new Map<string | number, number>();

  Object.values(valuesByAuthor).forEach((score) => {
    const displayValue = getDisplayValue(score);
    const numericValue = getNumericValue(score);
    
    const currentCount = valueCounts.get(displayValue) || 0;
    valueCounts.set(displayValue, currentCount + 1);
    valueMap.set(displayValue, numericValue);
  });

  return { valueCounts, valueMap };
};

// Format for parent rows: "avg X: Nx Emoji (Value) Nx Emoji (Value)"
const formatParentRowWithCounts = (
  valuesByAuthor: FeedbackScoreValueByAuthorMap,
  averageValue: string | number,
  categoryName?: string,
): string => {
  const { valueCounts, valueMap } = countValuesByDisplay(valuesByAuthor);

  const formattedParts = Array.from(valueCounts.entries())
    .sort((a, b) => {
      // Sort by count descending first
      if (b[1] !== a[1]) return b[1] - a[1];
      // Then by value for consistency
      return String(a[0]).localeCompare(String(b[0]));
    })
    .map(([value, count]) => `${count}x ${value} (${valueMap.get(value)})`)
    .join(" ");

  return formattedParts;
};

const renderParentValue = (displayText: string): React.ReactElement => (
  <div className="truncate text-light-slate">{displayText}</div>
);

const ValueCell: React.FC<ValueCellProps> = (context) => {
  const rowData = context.row.original;
  const value = context.getValue();
  // Use rowData.value directly for child rows to ensure we get the individual value
  const rowValue = rowData.value ?? value;

  const isParentRow = getIsParentFeedbackScoreRow(rowData);
  const isCategoricScore = getIsCategoricFeedbackScore(rowData.category_name);
  
  // Check if parent row has subRows with span_type (grouped by type)
  const isGroupedBySpanType = isParentRow && 
    rowData.subRows?.some(subRow => subRow.span_type);

  // Check if this is a span feedback score parent row (has subRows with span_id)
  const isSpanFeedbackScoreParent = isParentRow && 
    rowData.subRows?.some(subRow => subRow.span_id);

  const cellContent = useMemo((): string | React.ReactElement => {
    // Check if this is a span feedback score (has span_id or span_type in value_by_author)
    const isSpanFeedbackScore =
      rowData.span_id ||
      rowData.span_type ||
      Object.values(rowData.value_by_author ?? {}).some(
        (score) => score.span_id || score.span_type,
      );

    // For span feedback score parent rows, show "avg X: count format"
    if (isParentRow && (isGroupedBySpanType || isSpanFeedbackScoreParent) && rowData.value_by_author) {
      const countFormat = formatParentRowWithCounts(
        rowData.value_by_author,
        value,
        rowData.category_name,
      );
      return renderParentValue(`avg ${value}: ${countFormat}`);
    }

    // Categorical scores always use the categorical format
    if (isParentRow && isCategoricScore) {
      const displayText = formatCategoricScoreValue(
        rowData.value_by_author ?? {},
      );
      return renderParentValue(displayText);
    }

    if (isParentRow) {
      return renderParentValue(value);
    }

    // For child rows, display only the individual value
    // Extract from value_by_author to ensure we get the correct individual value
    if (!isParentRow && isSpanFeedbackScore) {
      const valueByAuthor = rowData.value_by_author ?? {};
      const entries = Object.values(valueByAuthor);
      
      if (entries.length > 0) {
        const singleScore = entries[0];
        const displayValue = getDisplayValue(singleScore);
        const numericValue = getNumericValue(singleScore);
        
        // Show display value with its numeric value in parentheses
        return `${displayValue} (${numericValue})`;
      }
      
      // Fallback to rowData.value if value_by_author is empty
      const fallbackValue = rowData.value ?? value;
      const fallbackNumeric = typeof fallbackValue === 'number' ? fallbackValue : parseFloat(String(fallbackValue)) || fallbackValue;
      
      if (rowData.category_name) {
        return categoryOptionLabelRenderer(rowData.category_name, rowData.value ?? value);
      }
      return `${fallbackValue} (${fallbackNumeric})`;
    }

    if (rowData.category_name) {
      return categoryOptionLabelRenderer(rowData.category_name, value);
    }

    return value;
  }, [
    isParentRow,
    isCategoricScore,
    isGroupedBySpanType,
    isSpanFeedbackScoreParent,
    rowData.value_by_author,
    rowData.category_name,
    rowData.span_id,
    rowData.span_type,
    rowData.subRows,
    rowData.value,
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
