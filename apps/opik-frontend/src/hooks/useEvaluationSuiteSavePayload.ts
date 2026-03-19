import { useCallback } from "react";
import { useQueryClient } from "@tanstack/react-query";
import useEvaluationSuiteDraftStore from "@/store/EvaluationSuiteDraftStore";
import { packAssertions } from "@/lib/assertion-converters";
import {
  DatasetItem,
  Dataset,
  DATASET_TYPE,
  Evaluator,
} from "@/types/datasets";
import { UseDatasetItemsListResponse } from "@/api/datasets/useDatasetItemsList";

interface BuildPayloadOptions {
  tags?: string[];
  changeDescription?: string;
  override?: boolean;
}

interface UseEvaluationSuiteSavePayloadOptions {
  suiteId: string;
  suite: Dataset | undefined;
  versionEvaluators: Evaluator[];
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

export function useEvaluationSuiteSavePayload({
  suiteId,
  suite,
  versionEvaluators,
}: UseEvaluationSuiteSavePayloadOptions) {
  const queryClient = useQueryClient();

  const buildPayload = useCallback(
    ({ tags, changeDescription, override = false }: BuildPayloadOptions) => {
      const state = useEvaluationSuiteDraftStore.getState();

      if (!suite) {
        throw new Error(
          "Evaluation suite data not available. Please refresh and try again.",
        );
      }

      const baseVersion = suite.latest_version?.id ?? "";
      if (!baseVersion) {
        throw new Error(
          "Base version is missing. The evaluation suite may not have been fully loaded.",
        );
      }

      const isEvaluationSuite = suite.type === DATASET_TYPE.EVALUATION_SUITE;

      // Serialize items from store
      const addedItemsList = Array.from(state.addedItems.values());
      const editedItemsMap = new Map(state.editedItems);
      const deletedIds = Array.from(state.deletedIds);

      let evaluators: Evaluator[] | undefined;
      const executionPolicy = state.executionPolicy ?? undefined;

      if (isEvaluationSuite) {
        // Suite-level: pack assertions into evaluator format
        if (state.suiteAssertions !== null) {
          const originalEvaluator = versionEvaluators[0];
          evaluators = [
            packAssertions(state.suiteAssertions, originalEvaluator),
          ];
        }

        // Item-level: iterate assertion overrides
        for (const [itemId, assertions] of state.itemAssertions) {
          if (state.addedItems.has(itemId)) {
            // Bake evaluators into the added item instead of creating an edit
            const idx = addedItemsList.findIndex((i) => i.id === itemId);
            if (idx !== -1) {
              addedItemsList[idx] = {
                ...addedItemsList[idx],
                evaluators: [packAssertions(assertions, undefined)],
              };
            }
            continue;
          }

          const cachedItem = findItemInCache(queryClient, suiteId, itemId);
          const originalEvaluator = cachedItem?.evaluators?.[0];
          const existingChanges = editedItemsMap.get(itemId) || {};
          editedItemsMap.set(itemId, {
            ...existingChanges,
            evaluators: [packAssertions(assertions, originalEvaluator)],
          });
        }
      }

      // Serialize edited items, converting store representation to API format.
      // The store preserves `execution_policy: undefined` to signal that the
      // user explicitly cleared an item-level override. The API expects a
      // `clear_execution_policy: true` flag instead of an undefined field.
      const editedItems = Array.from(editedItemsMap.entries()).map(
        ([id, changes]) => {
          const entry: Record<string, unknown> = { id };
          for (const [key, value] of Object.entries(changes)) {
            if (key === "execution_policy" && value === undefined) {
              entry.clear_execution_policy = true;
            } else if (value !== undefined) {
              entry[key] = value;
            }
          }
          return entry;
        },
      );

      return {
        datasetId: suiteId,
        payload: {
          added_items: addedItemsList,
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
    [suiteId, suite, versionEvaluators, queryClient],
  );

  return { buildPayload };
}
