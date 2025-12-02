import { ColumnSort } from "@tanstack/react-table";
import { SORT_DIRECTION, SortingField } from "@/types/sorting";
import {
  COLUMN_DURATION_ID,
  COLUMN_EXPERIMENT_SCORES_ID,
  COLUMN_FEEDBACK_SCORES_ID,
  COLUMN_USAGE_ID,
} from "@/types/shared";
import {
  EXPERIMENT_ITEM_DATASET_PREFIX,
  EXPERIMENT_ITEM_INPUT_PREFIX,
  EXPERIMENT_ITEM_METADATA_PREFIX,
  EXPERIMENT_ITEM_OUTPUT_PREFIX,
} from "@/constants/experiments";

// All column prefixes that need underscore to dot conversion for backend API
// (e.g., "feedback_scores_accuracy" -> "feedback_scores.accuracy")
const SORTABLE_PREFIXES = [
  COLUMN_FEEDBACK_SCORES_ID,
  COLUMN_EXPERIMENT_SCORES_ID,
  COLUMN_USAGE_ID,
  COLUMN_DURATION_ID,
  EXPERIMENT_ITEM_DATASET_PREFIX,
  EXPERIMENT_ITEM_OUTPUT_PREFIX,
  EXPERIMENT_ITEM_INPUT_PREFIX,
  EXPERIMENT_ITEM_METADATA_PREFIX,
];

export const mapComplexColumn = (column: ColumnSort): ColumnSort => {
  // Convert underscore to dot notation for all prefixed columns
  for (const prefix of SORTABLE_PREFIXES) {
    if (column.id.startsWith(prefix)) {
      return {
        ...column,
        id: column.id.replace(`${prefix}_`, `${prefix}.`),
      };
    }
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
      const { id, desc } = mapComplexColumn(column);

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
