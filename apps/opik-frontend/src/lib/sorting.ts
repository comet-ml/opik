import { ColumnSort } from "@tanstack/react-table";
import { SORT_DIRECTION, SortingField } from "@/types/sorting";
import { COLUMN_FEEDBACK_SCORES_ID } from "@/types/shared";

/**
 * Transforms column IDs prefixed with `usage_` into dot notation (`usage.`).
 * This is used to ensure compatibility with downstream sorting logic.
 */
export const mapUsageColumn = (column: ColumnSort): ColumnSort => {
  if (column.id.startsWith("usage_")) {
    return {
      ...column,
      id: column.id.replace("usage_", "usage."),
    };
  }
  return column;
};

export const processSorting = (sorting?: ColumnSort[]) => {
  const retVal: {
    sorting?: string;
  } = {};
  const sortingFields: SortingField[] = [];

  if (sorting && sorting.length > 0) {
    sorting.forEach((column) => {
      const { id, desc } = mapUsageColumn(mapFeedbackScoresColumn(column));

      sortingFields.push({
        field: id,
        direction: desc ? SORT_DIRECTION.DESC : SORT_DIRECTION.ASC,
      });
    });
  }

  if (sortingFields.length > 0) {
    retVal.sorting = JSON.stringify(sortingFields);
  }

  return retVal;
};

export const mapFeedbackScoresColumn = (column: ColumnSort): ColumnSort => {
  if (column.id.startsWith(COLUMN_FEEDBACK_SCORES_ID)) {
    return {
      ...column,
      id: column.id.replace(
        `${COLUMN_FEEDBACK_SCORES_ID}_`,
        `${COLUMN_FEEDBACK_SCORES_ID}.`,
      ),
    };
  }
  return column;
};
