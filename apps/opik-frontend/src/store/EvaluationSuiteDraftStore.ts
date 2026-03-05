import { create } from "zustand";
import { v4 as uuidv4 } from "uuid";
import { DatasetItem } from "@/types/datasets";
import {
  EvaluatorDisplayRow,
  ExecutionPolicy,
} from "@/types/evaluation-suites";

interface EvaluationSuiteDraftState {
  addedItems: Map<string, DatasetItem>;
  editedItems: Map<string, Partial<DatasetItem>>;
  deletedIds: Set<string>;
  isAllItemsSelected: boolean;

  addedEvaluators: Map<string, EvaluatorDisplayRow>;
  editedEvaluators: Map<string, Partial<EvaluatorDisplayRow>>;
  deletedEvaluatorIds: Set<string>;

  executionPolicy: ExecutionPolicy | null;

  itemAddedEvaluators: Map<string, Map<string, EvaluatorDisplayRow>>;
  itemEditedEvaluators: Map<string, Map<string, Partial<EvaluatorDisplayRow>>>;
  itemDeletedEvaluatorIds: Map<string, Set<string>>;

  addItem: (item: Omit<DatasetItem, "id">) => string;
  bulkAddItems: (items: Omit<DatasetItem, "id">[]) => void;
  editItem: (id: string, changes: Partial<DatasetItem>) => void;
  deleteItem: (id: string) => void;
  bulkDeleteItems: (ids: string[]) => void;
  bulkEditItems: (ids: string[], changes: Partial<DatasetItem>) => void;
  setIsAllItemsSelected: (value: boolean) => void;
  addEvaluator: (evaluator: Omit<EvaluatorDisplayRow, "id">) => string;
  editEvaluator: (id: string, changes: Partial<EvaluatorDisplayRow>) => void;
  deleteEvaluator: (id: string) => void;
  setExecutionPolicy: (policy: ExecutionPolicy) => void;

  addItemEvaluator: (
    itemId: string,
    evaluator: Omit<EvaluatorDisplayRow, "id">,
  ) => string;
  editItemEvaluator: (
    itemId: string,
    evaluatorId: string,
    changes: Partial<EvaluatorDisplayRow>,
  ) => void;
  deleteItemEvaluator: (itemId: string, evaluatorId: string) => void;

  clearDraft: () => void;
}

function createInitialState() {
  return {
    addedItems: new Map<string, DatasetItem>(),
    editedItems: new Map<string, Partial<DatasetItem>>(),
    deletedIds: new Set<string>(),
    isAllItemsSelected: false,
    addedEvaluators: new Map<string, EvaluatorDisplayRow>(),
    editedEvaluators: new Map<string, Partial<EvaluatorDisplayRow>>(),
    deletedEvaluatorIds: new Set<string>(),
    executionPolicy: null as ExecutionPolicy | null,
    itemAddedEvaluators: new Map<string, Map<string, EvaluatorDisplayRow>>(),
    itemEditedEvaluators: new Map<
      string,
      Map<string, Partial<EvaluatorDisplayRow>>
    >(),
    itemDeletedEvaluatorIds: new Map<string, Set<string>>(),
  };
}

function cloneNestedMap<V>(
  outer: Map<string, Map<string, V>>,
  key: string,
): { outerClone: Map<string, Map<string, V>>; inner: Map<string, V> } {
  const outerClone = new Map(outer);
  const inner = new Map(outerClone.get(key) ?? new Map<string, V>());
  outerClone.set(key, inner);
  return { outerClone, inner };
}

function hasNonEmptyEntries(map: Map<string, { size: number }>): boolean {
  for (const inner of map.values()) {
    if (inner.size > 0) return true;
  }
  return false;
}

function hasItemLevelChanges(state: EvaluationSuiteDraftState): boolean {
  return (
    hasNonEmptyEntries(state.itemAddedEvaluators) ||
    hasNonEmptyEntries(state.itemEditedEvaluators) ||
    hasNonEmptyEntries(state.itemDeletedEvaluatorIds)
  );
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

    addEvaluator: (evaluator) => {
      const id = uuidv4();
      set((state) => {
        const next = new Map(state.addedEvaluators);
        next.set(id, { ...evaluator, id, isNew: true });
        return { addedEvaluators: next };
      });
      return id;
    },

    editEvaluator: (id, changes) => {
      set((state) => {
        if (state.addedEvaluators.has(id)) {
          const next = new Map(state.addedEvaluators);
          const existing = next.get(id)!;
          next.set(id, { ...existing, ...changes });
          return { addedEvaluators: next };
        }
        const next = new Map(state.editedEvaluators);
        const existing = next.get(id) || {};
        next.set(id, { ...existing, ...changes, isEdited: true });
        return { editedEvaluators: next };
      });
    },

    deleteEvaluator: (id) => {
      set((state) => {
        if (state.addedEvaluators.has(id)) {
          const next = new Map(state.addedEvaluators);
          next.delete(id);
          return { addedEvaluators: next };
        }
        const nextEdited = new Map(state.editedEvaluators);
        nextEdited.delete(id);
        const nextDeleted = new Set(state.deletedEvaluatorIds);
        nextDeleted.add(id);
        return {
          editedEvaluators: nextEdited,
          deletedEvaluatorIds: nextDeleted,
        };
      });
    },

    setExecutionPolicy: (policy) => set({ executionPolicy: policy }),

    addItemEvaluator: (itemId, evaluator) => {
      const id = uuidv4();
      set((state) => {
        const { outerClone, inner } = cloneNestedMap(
          state.itemAddedEvaluators,
          itemId,
        );
        inner.set(id, { ...evaluator, id, isNew: true });
        return { itemAddedEvaluators: outerClone };
      });
      return id;
    },

    editItemEvaluator: (itemId, evaluatorId, changes) => {
      set((state) => {
        const addedInner = state.itemAddedEvaluators.get(itemId);
        if (addedInner?.has(evaluatorId)) {
          const { outerClone, inner } = cloneNestedMap(
            state.itemAddedEvaluators,
            itemId,
          );
          const existing = inner.get(evaluatorId)!;
          inner.set(evaluatorId, { ...existing, ...changes });
          return { itemAddedEvaluators: outerClone };
        }

        const { outerClone, inner } = cloneNestedMap(
          state.itemEditedEvaluators,
          itemId,
        );
        const existing = inner.get(evaluatorId) || {};
        inner.set(evaluatorId, { ...existing, ...changes, isEdited: true });
        return { itemEditedEvaluators: outerClone };
      });
    },

    deleteItemEvaluator: (itemId, evaluatorId) => {
      set((state) => {
        const addedInner = state.itemAddedEvaluators.get(itemId);
        if (addedInner?.has(evaluatorId)) {
          const { outerClone, inner } = cloneNestedMap(
            state.itemAddedEvaluators,
            itemId,
          );
          inner.delete(evaluatorId);
          return { itemAddedEvaluators: outerClone };
        }

        const { outerClone: editedOuter, inner: editedInner } = cloneNestedMap(
          state.itemEditedEvaluators,
          itemId,
        );
        editedInner.delete(evaluatorId);

        const deletedOuter = new Map(state.itemDeletedEvaluatorIds);
        const deletedInner = new Set<string>(
          deletedOuter.get(itemId) ?? new Set<string>(),
        );
        deletedInner.add(evaluatorId);
        deletedOuter.set(itemId, deletedInner);

        return {
          itemEditedEvaluators: editedOuter,
          itemDeletedEvaluatorIds: deletedOuter,
        };
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

export const selectHasEvaluatorChanges = (
  state: EvaluationSuiteDraftState,
): boolean =>
  state.addedEvaluators.size > 0 ||
  state.editedEvaluators.size > 0 ||
  state.deletedEvaluatorIds.size > 0 ||
  state.executionPolicy !== null ||
  hasItemLevelChanges(state);

export const selectHasDraft = (state: EvaluationSuiteDraftState): boolean =>
  selectIsDraftMode(state) || selectHasEvaluatorChanges(state);

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

// Suite-level evaluator hooks

export const useAddEvaluator = () =>
  useEvaluationSuiteDraftStore((state) => state.addEvaluator);
export const useEditEvaluator = () =>
  useEvaluationSuiteDraftStore((state) => state.editEvaluator);
export const useDeleteEvaluator = () =>
  useEvaluationSuiteDraftStore((state) => state.deleteEvaluator);
export const useSetExecutionPolicy = () =>
  useEvaluationSuiteDraftStore((state) => state.setExecutionPolicy);
export const useEvaluatorsExecutionPolicy = () =>
  useEvaluationSuiteDraftStore((state) => state.executionPolicy);
export const useAddedEvaluators = () =>
  useEvaluationSuiteDraftStore((state) => state.addedEvaluators);
export const useEditedEvaluators = () =>
  useEvaluationSuiteDraftStore((state) => state.editedEvaluators);
export const useDeletedEvaluatorIds = () =>
  useEvaluationSuiteDraftStore((state) => state.deletedEvaluatorIds);
export const useHasEvaluatorChanges = () =>
  useEvaluationSuiteDraftStore(selectHasEvaluatorChanges);

// Item-level evaluator hooks

const EMPTY_MAP = new Map<string, EvaluatorDisplayRow>();
const EMPTY_PARTIAL_MAP = new Map<string, Partial<EvaluatorDisplayRow>>();
const EMPTY_SET = new Set<string>();

export const useItemAddedEvaluators = (itemId: string) =>
  useEvaluationSuiteDraftStore(
    (state) => state.itemAddedEvaluators.get(itemId) ?? EMPTY_MAP,
  );
export const useItemEditedEvaluators = (itemId: string) =>
  useEvaluationSuiteDraftStore(
    (state) => state.itemEditedEvaluators.get(itemId) ?? EMPTY_PARTIAL_MAP,
  );
export const useItemDeletedEvaluatorIds = (itemId: string) =>
  useEvaluationSuiteDraftStore(
    (state) => state.itemDeletedEvaluatorIds.get(itemId) ?? EMPTY_SET,
  );

export const useAddItemEvaluator = () =>
  useEvaluationSuiteDraftStore((state) => state.addItemEvaluator);
export const useEditItemEvaluator = () =>
  useEvaluationSuiteDraftStore((state) => state.editItemEvaluator);
export const useDeleteItemEvaluator = () =>
  useEvaluationSuiteDraftStore((state) => state.deleteItemEvaluator);

export const useItemAddedEvaluatorsMap = () =>
  useEvaluationSuiteDraftStore((state) => state.itemAddedEvaluators);
export const useItemEditedEvaluatorsMap = () =>
  useEvaluationSuiteDraftStore((state) => state.itemEditedEvaluators);
export const useItemDeletedEvaluatorIdsMap = () =>
  useEvaluationSuiteDraftStore((state) => state.itemDeletedEvaluatorIds);

export default useEvaluationSuiteDraftStore;
