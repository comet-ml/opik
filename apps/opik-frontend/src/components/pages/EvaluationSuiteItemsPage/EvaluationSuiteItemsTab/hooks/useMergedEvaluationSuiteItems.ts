import { useMemo } from "react";
import {
  useAddedItems,
  useDeletedIds,
  useEditedItems,
  useIsDraftMode,
  useItemAddedEvaluatorsMap,
  useItemEditedEvaluatorsMap,
  useItemDeletedEvaluatorIdsMap,
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

function hasNonEmptyInner(
  map: Map<string, { size: number }>,
  id: string,
): boolean {
  const inner = map.get(id);
  return inner != null && inner.size > 0;
}

export const useEvaluationSuiteItemsWithDraft = (
  params: UseDatasetItemsListParams,
  options?: QueryConfig<UseDatasetItemsListResponse>,
) => {
  const isDraftMode = useIsDraftMode();
  const draftAddedItems = useAddedItems();
  const draftEditedItems = useEditedItems();
  const draftDeletedIds = useDeletedIds();
  const itemAddedEvaluators = useItemAddedEvaluatorsMap();
  const itemEditedEvaluators = useItemEditedEvaluatorsMap();
  const itemDeletedEvaluatorIds = useItemDeletedEvaluatorIdsMap();

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

    // Mark items with item-level evaluator changes as edited
    return items.map((item) => {
      if (
        item.draftStatus === DATASET_ITEM_DRAFT_STATUS.added ||
        item.draftStatus === DATASET_ITEM_DRAFT_STATUS.edited
      ) {
        return item;
      }

      const hasEvaluatorChanges =
        hasNonEmptyInner(itemAddedEvaluators, item.id) ||
        hasNonEmptyInner(itemEditedEvaluators, item.id) ||
        hasNonEmptyInner(itemDeletedEvaluatorIds, item.id);

      if (hasEvaluatorChanges) {
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
    itemAddedEvaluators,
    itemEditedEvaluators,
    itemDeletedEvaluatorIds,
  ]);

  return {
    ...query,
    data: query.data && {
      ...query.data,
      content: mergedContent,
    },
  };
};
