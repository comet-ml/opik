import { useMemo } from "react";
import {
  DatasetItem,
  DatasetItemWithDraft,
  DATASET_ITEM_DRAFT_STATUS,
} from "@/types/datasets";
import useDatasetDraftStore, {
  selectIsDraftMode,
} from "@/store/DatasetDraftStore";
import useDatasetItemsList, {
  UseDatasetItemsListParams,
  UseDatasetItemsListResponse,
} from "@/api/datasets/useDatasetItemsList";
import { QueryConfig } from "@/api/api";

/**
 * Merges API-fetched dataset items with local draft changes.
 * Applies the following transformations:
 * 1. Filters out deleted items
 * 2. Applies edits to existing items
 * 3. Adds new items at the top with "added" status
 */
const mergeItemsWithDraftChanges = (
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
  const newItems = Array.from(draftState.addedItems.values()).map((item) => ({
    ...item,
    draftStatus: DATASET_ITEM_DRAFT_STATUS.added,
  }));

  return [...newItems, ...processedApiItems];
};

/**
 * Hook that fetches dataset items and merges them with local draft changes.
 * Returns the full query object with content replaced by merged items.
 *
 * @param params - Dataset items query parameters
 * @param options - React Query options (placeholderData, refetchInterval, etc.)
 * @returns Full query object with merged dataset items in data.content
 */
export const useDatasetItemsWithDraft = (
  params: UseDatasetItemsListParams,
  options?: QueryConfig<UseDatasetItemsListResponse>,
) => {
  const isDraftMode = useDatasetDraftStore(selectIsDraftMode);
  const draftAddedItems = useDatasetDraftStore((state) => state.addedItems);
  const draftEditedItems = useDatasetDraftStore((state) => state.editedItems);
  const draftDeletedIds = useDatasetDraftStore((state) => state.deletedIds);

  const query = useDatasetItemsList(params, options);

  const mergedContent = useMemo(() => {
    const apiItems = query.data?.content ?? [];

    if (!isDraftMode) {
      return apiItems;
    }

    return mergeItemsWithDraftChanges(apiItems, {
      addedItems: draftAddedItems,
      editedItems: draftEditedItems,
      deletedIds: draftDeletedIds,
    });
  }, [
    query.data?.content,
    isDraftMode,
    draftAddedItems,
    draftEditedItems,
    draftDeletedIds,
  ]);

  return {
    ...query,
    data: query.data && {
      ...query.data,
      content: mergedContent,
    },
  };
};
