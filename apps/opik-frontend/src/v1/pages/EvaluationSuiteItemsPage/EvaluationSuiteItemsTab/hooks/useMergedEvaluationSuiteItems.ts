import { useMemo } from "react";
import {
  useAddedItems,
  useDeletedIds,
  useEditedItems,
  useIsDraftMode,
  useItemAssertionsMap,
} from "@/store/EvaluationSuiteDraftStore";
import useDatasetItemsList, {
  UseDatasetItemsListParams,
  UseDatasetItemsListResponse,
} from "@/api/datasets/useDatasetItemsList";
import { QueryConfig } from "@/api/api";
import { mergeItemsWithDraftChanges } from "@/lib/dataset-items";
import {
  DATASET_ITEM_DRAFT_STATUS,
  DatasetItemWithDraft,
} from "@/types/datasets";

export const useEvaluationSuiteItemsWithDraft = (
  params: UseDatasetItemsListParams,
  options?: QueryConfig<UseDatasetItemsListResponse>,
) => {
  const isDraftMode = useIsDraftMode();
  const draftAddedItems = useAddedItems();
  const draftEditedItems = useEditedItems();
  const draftDeletedIds = useDeletedIds();
  const itemAssertions = useItemAssertionsMap();

  const query = useDatasetItemsList(params, options);

  const mergedContent = useMemo(() => {
    const apiItems = query.data?.content ?? [];

    let items: DatasetItemWithDraft[];

    if (isDraftMode) {
      items = mergeItemsWithDraftChanges(apiItems, {
        addedItems: draftAddedItems,
        editedItems: draftEditedItems,
        deletedIds: draftDeletedIds,
      });
    } else {
      items = apiItems;
    }

    // Mark items with item-level assertion changes as edited
    return items.map((item) => {
      if (
        item.draftStatus === DATASET_ITEM_DRAFT_STATUS.added ||
        item.draftStatus === DATASET_ITEM_DRAFT_STATUS.edited
      ) {
        return item;
      }

      if (itemAssertions.has(item.id)) {
        return { ...item, draftStatus: DATASET_ITEM_DRAFT_STATUS.edited };
      }

      return item;
    });
  }, [
    query.data?.content,
    isDraftMode,
    draftAddedItems,
    draftEditedItems,
    draftDeletedIds,
    itemAssertions,
  ]);

  return {
    ...query,
    data: query.data && {
      ...query.data,
      content: mergedContent,
    },
  };
};
