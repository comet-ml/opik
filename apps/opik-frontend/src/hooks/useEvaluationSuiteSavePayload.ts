import { useCallback } from "react";
import { useQueryClient } from "@tanstack/react-query";
import useEvaluationSuiteDraftStore from "@/store/EvaluationSuiteDraftStore";
import { reconstructEvaluators } from "@/lib/evaluator-converters";
import {
  DatasetItem,
  DatasetVersion,
  Dataset,
  DATASET_TYPE,
  Evaluator,
} from "@/types/datasets";
import { BehaviorDisplayRow } from "@/types/evaluation-suites";
import { UseDatasetItemsListResponse } from "@/api/datasets/useDatasetItemsList";

interface BuildPayloadOptions {
  tags?: string[];
  changeDescription?: string;
  override?: boolean;
}

function findItemInCache(
  queryClient: ReturnType<typeof useQueryClient>,
  suiteId: string,
  itemId: string,
): DatasetItem | undefined {
  const queries = queryClient.getQueriesData<UseDatasetItemsListResponse>({
    queryKey: ["dataset-items", { datasetId: suiteId }],
  });
  for (const [, data] of queries) {
    const item = data?.content?.find((i) => i.id === itemId);
    if (item) return item;
  }
  return undefined;
}

export function useEvaluationSuiteSavePayload(suiteId: string) {
  const queryClient = useQueryClient();

  const buildPayload = useCallback(
    ({ tags, changeDescription, override = false }: BuildPayloadOptions) => {
      const state = useEvaluationSuiteDraftStore.getState();

      // Read suite data from cache
      const suiteData = queryClient.getQueryData<Dataset>([
        "dataset",
        { datasetId: suiteId },
      ]);
      if (!suiteData) {
        throw new Error(
          "Evaluation suite data not found in cache. Please refresh and try again.",
        );
      }

      const baseVersion = suiteData.latest_version?.id ?? "";
      if (!baseVersion) {
        throw new Error(
          "Base version is missing. The evaluation suite may not have been fully loaded.",
        );
      }

      const isEvaluationSuite =
        suiteData.type === DATASET_TYPE.EVALUATION_SUITE;

      // Serialize items from store
      const addedItems = Array.from(state.addedItems.values());
      const editedItemsMap = new Map(state.editedItems);
      const deletedIds = Array.from(state.deletedIds);

      let evaluators: Evaluator[] | undefined;
      const executionPolicy = state.executionPolicy ?? undefined;

      if (isEvaluationSuite) {
        // Read version data from cache
        const versionsData = queryClient.getQueryData<{
          content: DatasetVersion[];
        }>(["dataset-versions", { datasetId: suiteId, page: 1, size: 1 }]);
        const versionEvaluators = versionsData?.content?.[0]?.evaluators ?? [];

        // Reconstruct suite-level evaluators
        evaluators = reconstructEvaluators(
          versionEvaluators,
          state.addedBehaviors,
          state.editedBehaviors,
          state.deletedBehaviorIds,
        );

        // Collect all itemIds that have item-level behavior changes
        const itemIdsWithChanges = new Set<string>();
        const behaviorMaps = [
          state.itemAddedBehaviors,
          state.itemEditedBehaviors,
          state.itemDeletedBehaviorIds,
        ];
        for (const map of behaviorMaps) {
          for (const [itemId, inner] of map) {
            if (inner.size > 0) {
              itemIdsWithChanges.add(itemId);
            }
          }
        }

        // For each item with behavior changes, reconstruct its evaluators
        for (const itemId of itemIdsWithChanges) {
          const cachedItem = findItemInCache(queryClient, suiteId, itemId);
          const originalItemEvaluators = cachedItem?.evaluators ?? [];
          const itemAdded =
            state.itemAddedBehaviors.get(itemId) ??
            new Map<string, BehaviorDisplayRow>();
          const itemEdited =
            state.itemEditedBehaviors.get(itemId) ??
            new Map<string, Partial<BehaviorDisplayRow>>();
          const itemDeleted =
            state.itemDeletedBehaviorIds.get(itemId) ?? new Set<string>();

          const itemEvaluators = reconstructEvaluators(
            originalItemEvaluators,
            itemAdded,
            itemEdited,
            itemDeleted,
          );

          // Merge evaluators into the edited_items entry
          const existingChanges = editedItemsMap.get(itemId) || {};
          editedItemsMap.set(itemId, {
            ...existingChanges,
            evaluators: itemEvaluators,
          });
        }
      }

      // Serialize edited items, filtering out undefined values
      const editedItems = Array.from(editedItemsMap.entries()).map(
        ([id, changes]) => {
          const entry: Partial<DatasetItem> & { id: string } = { id };
          for (const [key, value] of Object.entries(changes)) {
            if (value !== undefined) {
              (entry as Record<string, unknown>)[key] = value;
            }
          }
          return entry;
        },
      );

      return {
        datasetId: suiteId,
        payload: {
          added_items: addedItems,
          edited_items: editedItems,
          deleted_ids: deletedIds,
          base_version: baseVersion,
          tags,
          change_description: changeDescription,
          ...(evaluators !== undefined && { evaluators }),
          ...(executionPolicy !== undefined && {
            execution_policy: executionPolicy,
          }),
        },
        override,
      };
    },
    [suiteId, queryClient],
  );

  return { buildPayload };
}
