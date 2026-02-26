import { create } from "zustand";
import { v4 as uuidv4 } from "uuid";
import { DatasetItem } from "@/types/datasets";
import { BehaviorDisplayRow, ExecutionPolicy } from "@/types/evaluation-suites";

interface EvaluationSuiteDraftState {
  addedItems: Map<string, DatasetItem>;
  editedItems: Map<string, Partial<DatasetItem>>;
  deletedIds: Set<string>;
  isAllItemsSelected: boolean;

  addedBehaviors: Map<string, BehaviorDisplayRow>;
  editedBehaviors: Map<string, Partial<BehaviorDisplayRow>>;
  deletedBehaviorIds: Set<string>;

  executionPolicy: ExecutionPolicy | null;

  itemAddedBehaviors: Map<string, Map<string, BehaviorDisplayRow>>;
  itemEditedBehaviors: Map<string, Map<string, Partial<BehaviorDisplayRow>>>;
  itemDeletedBehaviorIds: Map<string, Set<string>>;

  addItem: (item: Omit<DatasetItem, "id">) => void;
  bulkAddItems: (items: Omit<DatasetItem, "id">[]) => void;
  editItem: (id: string, changes: Partial<DatasetItem>) => void;
  deleteItem: (id: string) => void;
  bulkDeleteItems: (ids: string[]) => void;
  bulkEditItems: (ids: string[], changes: Partial<DatasetItem>) => void;
  setIsAllItemsSelected: (value: boolean) => void;
  addBehavior: (behavior: Omit<BehaviorDisplayRow, "id">) => string;
  editBehavior: (id: string, changes: Partial<BehaviorDisplayRow>) => void;
  deleteBehavior: (id: string) => void;
  setExecutionPolicy: (policy: ExecutionPolicy) => void;

  addItemBehavior: (
    itemId: string,
    behavior: Omit<BehaviorDisplayRow, "id">,
  ) => string;
  editItemBehavior: (
    itemId: string,
    behaviorId: string,
    changes: Partial<BehaviorDisplayRow>,
  ) => void;
  deleteItemBehavior: (itemId: string, behaviorId: string) => void;

  clearDraft: () => void;
}

function createInitialState() {
  return {
    addedItems: new Map<string, DatasetItem>(),
    editedItems: new Map<string, Partial<DatasetItem>>(),
    deletedIds: new Set<string>(),
    isAllItemsSelected: false,
    addedBehaviors: new Map<string, BehaviorDisplayRow>(),
    editedBehaviors: new Map<string, Partial<BehaviorDisplayRow>>(),
    deletedBehaviorIds: new Set<string>(),
    executionPolicy: null as ExecutionPolicy | null,
    itemAddedBehaviors: new Map<string, Map<string, BehaviorDisplayRow>>(),
    itemEditedBehaviors: new Map<
      string,
      Map<string, Partial<BehaviorDisplayRow>>
    >(),
    itemDeletedBehaviorIds: new Map<string, Set<string>>(),
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
    hasNonEmptyEntries(state.itemAddedBehaviors) ||
    hasNonEmptyEntries(state.itemEditedBehaviors) ||
    hasNonEmptyEntries(state.itemDeletedBehaviorIds)
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

    addBehavior: (behavior) => {
      const id = uuidv4();
      set((state) => {
        const next = new Map(state.addedBehaviors);
        next.set(id, { ...behavior, id, isNew: true });
        return { addedBehaviors: next };
      });
      return id;
    },

    editBehavior: (id, changes) => {
      set((state) => {
        if (state.addedBehaviors.has(id)) {
          const next = new Map(state.addedBehaviors);
          const existing = next.get(id)!;
          next.set(id, { ...existing, ...changes });
          return { addedBehaviors: next };
        }
        const next = new Map(state.editedBehaviors);
        const existing = next.get(id) || {};
        next.set(id, { ...existing, ...changes, isEdited: true });
        return { editedBehaviors: next };
      });
    },

    deleteBehavior: (id) => {
      set((state) => {
        if (state.addedBehaviors.has(id)) {
          const next = new Map(state.addedBehaviors);
          next.delete(id);
          return { addedBehaviors: next };
        }
        const nextEdited = new Map(state.editedBehaviors);
        nextEdited.delete(id);
        const nextDeleted = new Set(state.deletedBehaviorIds);
        nextDeleted.add(id);
        return {
          editedBehaviors: nextEdited,
          deletedBehaviorIds: nextDeleted,
        };
      });
    },

    setExecutionPolicy: (policy) => set({ executionPolicy: policy }),

    addItemBehavior: (itemId, behavior) => {
      const id = uuidv4();
      set((state) => {
        const { outerClone, inner } = cloneNestedMap(
          state.itemAddedBehaviors,
          itemId,
        );
        inner.set(id, { ...behavior, id, isNew: true });
        return { itemAddedBehaviors: outerClone };
      });
      return id;
    },

    editItemBehavior: (itemId, behaviorId, changes) => {
      set((state) => {
        const addedInner = state.itemAddedBehaviors.get(itemId);
        if (addedInner?.has(behaviorId)) {
          const { outerClone, inner } = cloneNestedMap(
            state.itemAddedBehaviors,
            itemId,
          );
          const existing = inner.get(behaviorId)!;
          inner.set(behaviorId, { ...existing, ...changes });
          return { itemAddedBehaviors: outerClone };
        }

        const { outerClone, inner } = cloneNestedMap(
          state.itemEditedBehaviors,
          itemId,
        );
        const existing = inner.get(behaviorId) || {};
        inner.set(behaviorId, { ...existing, ...changes, isEdited: true });
        return { itemEditedBehaviors: outerClone };
      });
    },

    deleteItemBehavior: (itemId, behaviorId) => {
      set((state) => {
        const addedInner = state.itemAddedBehaviors.get(itemId);
        if (addedInner?.has(behaviorId)) {
          const { outerClone, inner } = cloneNestedMap(
            state.itemAddedBehaviors,
            itemId,
          );
          inner.delete(behaviorId);
          return { itemAddedBehaviors: outerClone };
        }

        const { outerClone: editedOuter, inner: editedInner } = cloneNestedMap(
          state.itemEditedBehaviors,
          itemId,
        );
        editedInner.delete(behaviorId);

        const deletedOuter = new Map(state.itemDeletedBehaviorIds);
        const deletedInner = new Set<string>(
          deletedOuter.get(itemId) ?? new Set<string>(),
        );
        deletedInner.add(behaviorId);
        deletedOuter.set(itemId, deletedInner);

        return {
          itemEditedBehaviors: editedOuter,
          itemDeletedBehaviorIds: deletedOuter,
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

export const selectHasBehaviorChanges = (
  state: EvaluationSuiteDraftState,
): boolean =>
  state.addedBehaviors.size > 0 ||
  state.editedBehaviors.size > 0 ||
  state.deletedBehaviorIds.size > 0 ||
  state.executionPolicy !== null ||
  hasItemLevelChanges(state);

export const selectHasDraft = (state: EvaluationSuiteDraftState): boolean =>
  selectIsDraftMode(state) || selectHasBehaviorChanges(state);

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

// Suite-level behavior hooks

export const useAddBehavior = () =>
  useEvaluationSuiteDraftStore((state) => state.addBehavior);
export const useEditBehavior = () =>
  useEvaluationSuiteDraftStore((state) => state.editBehavior);
export const useDeleteBehavior = () =>
  useEvaluationSuiteDraftStore((state) => state.deleteBehavior);
export const useSetExecutionPolicy = () =>
  useEvaluationSuiteDraftStore((state) => state.setExecutionPolicy);
export const useBehaviorsExecutionPolicy = () =>
  useEvaluationSuiteDraftStore((state) => state.executionPolicy);
export const useAddedBehaviors = () =>
  useEvaluationSuiteDraftStore((state) => state.addedBehaviors);
export const useEditedBehaviors = () =>
  useEvaluationSuiteDraftStore((state) => state.editedBehaviors);
export const useDeletedBehaviorIds = () =>
  useEvaluationSuiteDraftStore((state) => state.deletedBehaviorIds);
export const useHasBehaviorChanges = () =>
  useEvaluationSuiteDraftStore(selectHasBehaviorChanges);

// Item-level behavior hooks

const EMPTY_MAP = new Map<string, BehaviorDisplayRow>();
const EMPTY_PARTIAL_MAP = new Map<string, Partial<BehaviorDisplayRow>>();
const EMPTY_SET = new Set<string>();

export const useItemAddedBehaviors = (itemId: string) =>
  useEvaluationSuiteDraftStore(
    (state) => state.itemAddedBehaviors.get(itemId) ?? EMPTY_MAP,
  );
export const useItemEditedBehaviors = (itemId: string) =>
  useEvaluationSuiteDraftStore(
    (state) => state.itemEditedBehaviors.get(itemId) ?? EMPTY_PARTIAL_MAP,
  );
export const useItemDeletedBehaviorIds = (itemId: string) =>
  useEvaluationSuiteDraftStore(
    (state) => state.itemDeletedBehaviorIds.get(itemId) ?? EMPTY_SET,
  );

export const useAddItemBehavior = () =>
  useEvaluationSuiteDraftStore((state) => state.addItemBehavior);
export const useEditItemBehavior = () =>
  useEvaluationSuiteDraftStore((state) => state.editItemBehavior);
export const useDeleteItemBehavior = () =>
  useEvaluationSuiteDraftStore((state) => state.deleteItemBehavior);

export const useItemAddedBehaviorsMap = () =>
  useEvaluationSuiteDraftStore((state) => state.itemAddedBehaviors);
export const useItemEditedBehaviorsMap = () =>
  useEvaluationSuiteDraftStore((state) => state.itemEditedBehaviors);
export const useItemDeletedBehaviorIdsMap = () =>
  useEvaluationSuiteDraftStore((state) => state.itemDeletedBehaviorIds);

export default useEvaluationSuiteDraftStore;
