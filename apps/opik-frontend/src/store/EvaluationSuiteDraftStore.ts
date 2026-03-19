import { create } from "zustand";
import { useShallow } from "zustand/react/shallow";
import { v4 as uuidv4 } from "uuid";
import isEqual from "lodash/isEqual";
import { DatasetItem } from "@/types/datasets";
import { ExecutionPolicy } from "@/types/evaluation-suites";

interface EvaluationSuiteDraftState {
  addedItems: Map<string, DatasetItem>;
  editedItems: Map<string, Partial<DatasetItem>>;
  deletedIds: Set<string>;
  isAllItemsSelected: boolean;

  suiteAssertions: string[] | null;
  itemAssertions: Map<string, string[]>;
  executionPolicy: ExecutionPolicy | null;

  addItem: (item: Omit<DatasetItem, "id">) => string;
  bulkAddItems: (items: Omit<DatasetItem, "id">[]) => void;
  editItem: (id: string, changes: Partial<DatasetItem>) => void;
  deleteItem: (id: string) => void;
  bulkDeleteItems: (ids: string[]) => void;
  bulkEditItems: (ids: string[], changes: Partial<DatasetItem>) => void;
  setIsAllItemsSelected: (value: boolean) => void;

  setItemAssertions: (itemId: string, assertions: string[]) => void;
  updateItemAssertions: (
    itemId: string,
    newAssertions: string[],
    serverAssertions: string[],
  ) => void;

  updateSuiteAssertions: (
    newAssertions: string[],
    serverAssertions: string[],
  ) => void;
  updateExecutionPolicy: (
    newPolicy: ExecutionPolicy,
    serverPolicy: ExecutionPolicy,
  ) => void;

  clearDraft: () => void;
}

function createInitialState() {
  return {
    addedItems: new Map<string, DatasetItem>(),
    editedItems: new Map<string, Partial<DatasetItem>>(),
    deletedIds: new Set<string>(),
    isAllItemsSelected: false,
    suiteAssertions: null as string[] | null,
    itemAssertions: new Map<string, string[]>(),
    executionPolicy: null as ExecutionPolicy | null,
  };
}

function mergeEditedItem(
  editedItems: Map<string, Partial<DatasetItem>>,
  id: string,
  changes: Partial<DatasetItem>,
): void {
  const existing = editedItems.get(id) || {};
  editedItems.set(id, { ...existing, ...changes });
}

const useEvaluationSuiteDraftStore = create<EvaluationSuiteDraftState>(
  (set, get) => ({
    ...createInitialState(),

    addItem: (item) => {
      const tempId = uuidv4();
      const newItem: DatasetItem = { ...item, id: tempId };

      set((state) => {
        const newAddedItems = new Map(state.addedItems);
        newAddedItems.set(tempId, newItem);
        return { addedItems: newAddedItems, isAllItemsSelected: false };
      });

      return tempId;
    },

    bulkAddItems: (items) => {
      set((state) => {
        const newAddedItems = new Map(state.addedItems);
        items.forEach((item) => {
          const tempId = uuidv4();
          newAddedItems.set(tempId, { ...item, id: tempId });
        });
        return { addedItems: newAddedItems, isAllItemsSelected: false };
      });
    },

    editItem: (id, changes) => {
      set((state) => {
        if (state.addedItems.has(id)) {
          const newAddedItems = new Map(state.addedItems);
          const existingItem = state.addedItems.get(id)!;
          newAddedItems.set(id, { ...existingItem, ...changes });
          return { addedItems: newAddedItems, isAllItemsSelected: false };
        }

        const newEditedItems = new Map(state.editedItems);
        mergeEditedItem(newEditedItems, id, changes);
        return { editedItems: newEditedItems, isAllItemsSelected: false };
      });
    },

    deleteItem: (id) => {
      get().bulkDeleteItems([id]);
    },

    bulkDeleteItems: (ids) => {
      set((state) => {
        const newAddedItems = new Map(state.addedItems);
        const newEditedItems = new Map(state.editedItems);
        const newDeletedIds = new Set(state.deletedIds);

        ids.forEach((id) => {
          if (state.addedItems.has(id)) {
            newAddedItems.delete(id);
          } else {
            newEditedItems.delete(id);
            newDeletedIds.add(id);
          }
        });

        return {
          addedItems: newAddedItems,
          editedItems: newEditedItems,
          deletedIds: newDeletedIds,
          isAllItemsSelected: false,
        };
      });
    },

    bulkEditItems: (ids, changes) => {
      set((state) => {
        const newAddedItems = new Map(state.addedItems);
        const newEditedItems = new Map(state.editedItems);

        ids.forEach((id) => {
          if (state.addedItems.has(id)) {
            const existingItem = state.addedItems.get(id)!;
            newAddedItems.set(id, { ...existingItem, ...changes });
          } else {
            mergeEditedItem(newEditedItems, id, changes);
          }
        });

        return {
          addedItems: newAddedItems,
          editedItems: newEditedItems,
          isAllItemsSelected: false,
        };
      });
    },

    setIsAllItemsSelected: (value) => {
      set({ isAllItemsSelected: value });
    },

    setItemAssertions: (itemId, assertions) => {
      set((state) => {
        const next = new Map(state.itemAssertions);
        next.set(itemId, assertions);
        return { itemAssertions: next };
      });
    },

    updateItemAssertions: (itemId, newAssertions, serverAssertions) => {
      set((state) => {
        if (isEqual(newAssertions, serverAssertions)) {
          if (!state.itemAssertions.has(itemId)) return state;
          const next = new Map(state.itemAssertions);
          next.delete(itemId);
          return { itemAssertions: next };
        }
        const next = new Map(state.itemAssertions);
        next.set(itemId, newAssertions);
        return { itemAssertions: next };
      });
    },

    updateSuiteAssertions: (newAssertions, serverAssertions) => {
      set({
        suiteAssertions: isEqual(newAssertions, serverAssertions)
          ? null
          : newAssertions,
      });
    },

    updateExecutionPolicy: (newPolicy, serverPolicy) => {
      set({
        executionPolicy: isEqual(newPolicy, serverPolicy) ? null : newPolicy,
      });
    },

    clearDraft: () => {
      set(createInitialState());
    },
  }),
);

// Selectors

export const selectIsDraftMode = (state: EvaluationSuiteDraftState) =>
  state.addedItems.size > 0 ||
  state.editedItems.size > 0 ||
  state.deletedIds.size > 0;

export const selectHasAssertionChanges = (
  state: EvaluationSuiteDraftState,
): boolean =>
  state.suiteAssertions !== null ||
  state.itemAssertions.size > 0 ||
  state.executionPolicy !== null;

export const selectHasDraft = (state: EvaluationSuiteDraftState): boolean =>
  selectIsDraftMode(state) || selectHasAssertionChanges(state);

// Item CRUD hooks

export const useAddItem = () =>
  useEvaluationSuiteDraftStore((state) => state.addItem);
export const useBulkAddItems = () =>
  useEvaluationSuiteDraftStore((state) => state.bulkAddItems);
export const useEditItem = () =>
  useEvaluationSuiteDraftStore((state) => state.editItem);
export const useDeleteItem = () =>
  useEvaluationSuiteDraftStore((state) => state.deleteItem);
export const useBulkDeleteItems = () =>
  useEvaluationSuiteDraftStore((state) => state.bulkDeleteItems);
export const useBulkEditItems = () =>
  useEvaluationSuiteDraftStore((state) => state.bulkEditItems);
export const useClearDraft = () =>
  useEvaluationSuiteDraftStore((state) => state.clearDraft);
export const useAddedItems = () =>
  useEvaluationSuiteDraftStore((state) => state.addedItems);
export const useEditedItems = () =>
  useEvaluationSuiteDraftStore((state) => state.editedItems);
export const useDeletedIds = () =>
  useEvaluationSuiteDraftStore((state) => state.deletedIds);

export const useAddedDatasetItemById = (id?: string) =>
  useEvaluationSuiteDraftStore((state) => state.addedItems.get(id || ""));
export const useEditedDatasetItemById = (id?: string) =>
  useEvaluationSuiteDraftStore((state) => state.editedItems.get(id || ""));
export const useIsDraftMode = () =>
  useEvaluationSuiteDraftStore(selectIsDraftMode);
export const useHasDraft = () => useEvaluationSuiteDraftStore(selectHasDraft);

export const useIsAllItemsSelected = () =>
  useEvaluationSuiteDraftStore((state) => state.isAllItemsSelected);
export const useSetIsAllItemsSelected = () =>
  useEvaluationSuiteDraftStore((state) => state.setIsAllItemsSelected);

// Assertion hooks

export const useSuiteAssertions = () =>
  useEvaluationSuiteDraftStore((state) => state.suiteAssertions);
export const useItemAssertions = (itemId: string) =>
  useEvaluationSuiteDraftStore((state) => state.itemAssertions.get(itemId));
export const useItemAssertionsMap = () =>
  useEvaluationSuiteDraftStore((state) => state.itemAssertions);
export const useUpdateItemAssertions = () =>
  useEvaluationSuiteDraftStore((state) => state.updateItemAssertions);

// Execution policy hooks

export const useDraftExecutionPolicy = () =>
  useEvaluationSuiteDraftStore((state) => state.executionPolicy);

// Consolidated action hooks — returns all action functions in a single call.
// Actions are stable references in Zustand, so grouping them doesn't cause
// extra re-renders when used with useShallow.

export const useDraftAssertionActions = () =>
  useEvaluationSuiteDraftStore(
    useShallow((state) => ({
      updateSuiteAssertions: state.updateSuiteAssertions,
      updateExecutionPolicy: state.updateExecutionPolicy,
    })),
  );

export default useEvaluationSuiteDraftStore;
