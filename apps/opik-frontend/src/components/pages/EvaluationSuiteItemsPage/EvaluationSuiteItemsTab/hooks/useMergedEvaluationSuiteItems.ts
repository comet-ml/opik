import { useMemo } from "react";
import {
  useAddedItems,
  useDeletedIds,
  useEditedItems,
  useIsDraftMode,
} from "@/store/EvaluationSuiteDraftStore";
import useDatasetItemsList, {
  UseDatasetItemsListParams,
  UseDatasetItemsListResponse,
} from "@/api/datasets/useDatasetItemsList";
import { QueryConfig } from "@/api/api";
import { mergeItemsWithDraftChanges } from "@/lib/dataset-items";

export const useEvaluationSuiteItemsWithDraft = (
  params: UseDatasetItemsListParams,
  options?: QueryConfig<UseDatasetItemsListResponse>,
) => {
  const isDraftMode = useIsDraftMode();
  const draftAddedItems = useAddedItems();
  const draftEditedItems = useEditedItems();
  const draftDeletedIds = useDeletedIds();

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
