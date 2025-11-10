import { ColumnSort } from "@tanstack/react-table";
import { SORT_DIRECTION, SortingField } from "@/types/sorting";
import {
  COLUMN_DURATION_ID,
  COLUMN_FEEDBACK_SCORES_ID,
  COLUMN_USAGE_ID,
} from "@/types/shared";
import {
  EXPERIMENT_ITEM_DATASET_PREFIX,
  EXPERIMENT_ITEM_INPUT_PREFIX,
  EXPERIMENT_ITEM_METADATA_PREFIX,
  EXPERIMENT_ITEM_OUTPUT_PREFIX,
} from "@/constants/experiments";

export const mapComplexColumn = (column: ColumnSort): ColumnSort => {
  if (column.id.startsWith(COLUMN_FEEDBACK_SCORES_ID)) {
    return {
      ...column,
      id: column.id.replace(
        `${COLUMN_FEEDBACK_SCORES_ID}_`,
        `${COLUMN_FEEDBACK_SCORES_ID}.`,
      ),
    };
  }

  if (column.id.startsWith(COLUMN_USAGE_ID)) {
    return {
      ...column,
      id: column.id.replace(`${COLUMN_USAGE_ID}_`, `${COLUMN_USAGE_ID}.`),
    };
  }

  if (column.id.startsWith(COLUMN_DURATION_ID)) {
    return {
      ...column,
      id: column.id.replace(`${COLUMN_DURATION_ID}_`, `${COLUMN_DURATION_ID}.`),
    };
  }

  // Handle experiment item prefixes (data, output, input, metadata)
  // Convert underscore to dot notation for backend API (e.g., "data_field" -> "data.field")
  const experimentPrefixes = [
    EXPERIMENT_ITEM_DATASET_PREFIX,
    EXPERIMENT_ITEM_OUTPUT_PREFIX,
    EXPERIMENT_ITEM_INPUT_PREFIX,
    EXPERIMENT_ITEM_METADATA_PREFIX,
  ];

  for (const prefix of experimentPrefixes) {
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
