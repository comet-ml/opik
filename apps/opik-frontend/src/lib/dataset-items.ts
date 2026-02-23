import {
  DatasetItem,
  DatasetItemWithDraft,
  DATASET_ITEM_DRAFT_STATUS,
} from "@/types/datasets";
import { Filters } from "@/types/filters";
import { COLUMN_DATA_ID } from "@/types/shared";

export const mergeItemsWithDraftChanges = (
  apiItems: DatasetItem[],
  draftState: {
    addedItems: Map<string, DatasetItem>;
    editedItems: Map<string, Partial<DatasetItem>>;
    deletedIds: Set<string>;
  },
): DatasetItemWithDraft[] => {
  // Filter out deleted items and apply edits to existing items
  const processedApiItems = apiItems
    .filter((item) => !draftState.deletedIds.has(item.id))
    .map((item) => {
      const editedFields = draftState.editedItems.get(item.id);
      if (editedFields) {
        return {
          ...item,
          ...editedFields,
          draftStatus: DATASET_ITEM_DRAFT_STATUS.edited,
        };
      }
      return item;
    });

  // Prepend new draft items at the top
  const newItems = Array.from(draftState.addedItems.values())
    .reverse()
    .map((item) => ({
      ...item,
      draftStatus: DATASET_ITEM_DRAFT_STATUS.added,
    }));

  return [...newItems, ...processedApiItems];
};

export const transformDataColumnFilters = (filters: Filters): Filters => {
  const dataFieldPrefix = `${COLUMN_DATA_ID}.`;

  return filters.map((filter) => {
    if (filter.field.startsWith(dataFieldPrefix)) {
      const columnKey = filter.field.slice(dataFieldPrefix.length);
      return {
        ...filter,
        field: COLUMN_DATA_ID,
        key: columnKey,
      };
    }
    return filter;
  });
};
