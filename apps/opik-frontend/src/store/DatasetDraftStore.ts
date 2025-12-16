import { create } from "zustand";
import { v4 as uuidv4 } from "uuid";
import { DatasetItem } from "@/types/datasets";

interface DatasetDraftState {
  // State
  addedItems: Map<string, DatasetItem>;
  editedItems: Map<string, Partial<DatasetItem>>;
  deletedIds: Set<string>;

  // Actions
  addItem: (item: Omit<DatasetItem, "id">) => void;
  editItem: (id: string, changes: Partial<DatasetItem>) => void;
  deleteItem: (id: string) => void;
  bulkDeleteItems: (ids: string[]) => void;
  bulkEditItems: (ids: string[], changes: Partial<DatasetItem>) => void;
  clearDraft: () => void;
  getChangesSummary: () => { added: number; edited: number; deleted: number };
  getChangesPayload: () => {
    addedItems: DatasetItem[];
    editedItems: DatasetItem[];
    deletedIds: string[];
  };
}

const useDatasetDraftStore = create<DatasetDraftState>((set, get) => ({
  // Initial state
  addedItems: new Map(),
  editedItems: new Map(),
  deletedIds: new Set(),

  // Actions

  addItem: (item: Omit<DatasetItem, "id">) => {
    const tempId = uuidv4();
    const newItem: DatasetItem = {
      ...item,
      id: tempId,
    };

    set((state) => {
      const newAddedItems = new Map(state.addedItems);
      newAddedItems.set(tempId, newItem);
      return { addedItems: newAddedItems };
    });
  },

  editItem: (id: string, changes: Partial<DatasetItem>) => {
    set((state) => {
      // Check if this is a temp item (from addedItems)
      if (state.addedItems.has(id)) {
        const newAddedItems = new Map(state.addedItems);
        const existingItem = state.addedItems.get(id)!;
        newAddedItems.set(id, { ...existingItem, ...changes });
        return { addedItems: newAddedItems };
      }

      // Otherwise, it's an edit to an existing item
      const newEditedItems = new Map(state.editedItems);
      const existingChanges = state.editedItems.get(id) || {};
      newEditedItems.set(id, { ...existingChanges, ...changes });
      return { editedItems: newEditedItems };
    });
  },

  deleteItem: (id: string) => {
    set((state) => {
      // If it's a temp item, just remove it from addedItems
      if (state.addedItems.has(id)) {
        const newAddedItems = new Map(state.addedItems);
        newAddedItems.delete(id);
        return { addedItems: newAddedItems };
      }

      // If it's an edited item being deleted, remove from editedItems
      const newEditedItems = new Map(state.editedItems);
      newEditedItems.delete(id);

      // Add to deletedIds
      const newDeletedIds = new Set(state.deletedIds);
      newDeletedIds.add(id);

      return {
        editedItems: newEditedItems,
        deletedIds: newDeletedIds,
      };
    });
  },

  bulkDeleteItems: (ids: string[]) => {
    set((state) => {
      const newAddedItems = new Map(state.addedItems);
      const newEditedItems = new Map(state.editedItems);
      const newDeletedIds = new Set(state.deletedIds);

      ids.forEach((id) => {
        // If it's a temp item, just remove it from addedItems
        if (state.addedItems.has(id)) {
          newAddedItems.delete(id);
        } else {
          // If it's an edited item being deleted, remove from editedItems
          newEditedItems.delete(id);
          // Add to deletedIds
          newDeletedIds.add(id);
        }
      });

      return {
        addedItems: newAddedItems,
        editedItems: newEditedItems,
        deletedIds: newDeletedIds,
      };
    });
  },

  bulkEditItems: (ids: string[], changes: Partial<DatasetItem>) => {
    set((state) => {
      const newAddedItems = new Map(state.addedItems);
      const newEditedItems = new Map(state.editedItems);

      ids.forEach((id) => {
        // Check if this is a temp item (from addedItems)
        if (state.addedItems.has(id)) {
          const existingItem = state.addedItems.get(id)!;
          newAddedItems.set(id, { ...existingItem, ...changes });
        } else {
          // Otherwise, it's an edit to an existing item
          const existingChanges = state.editedItems.get(id) || {};
          newEditedItems.set(id, { ...existingChanges, ...changes });
        }
      });

      return {
        addedItems: newAddedItems,
        editedItems: newEditedItems,
      };
    });
  },

  clearDraft: () => {
    set({
      addedItems: new Map(),
      editedItems: new Map(),
      deletedIds: new Set(),
    });
  },

  getChangesSummary: () => {
    const state = get();
    return {
      added: state.addedItems.size,
      edited: state.editedItems.size,
      deleted: state.deletedIds.size,
    };
  },

  getChangesPayload: () => {
    const state = get();
    return {
      addedItems: Array.from(state.addedItems.values()),
      editedItems: Array.from(state.editedItems.entries()).map(
        ([id, changes]) =>
          ({
            id,
            ...changes,
          }) as DatasetItem,
      ),
      deletedIds: Array.from(state.deletedIds),
    };
  },
}));

// Selectors
export const selectIsDraftMode = (state: DatasetDraftState) =>
  state.addedItems.size > 0 ||
  state.editedItems.size > 0 ||
  state.deletedIds.size > 0;

// Custom hooks (following AppStore.ts pattern)
export const useAddItem = () => useDatasetDraftStore((state) => state.addItem);
export const useEditItem = () =>
  useDatasetDraftStore((state) => state.editItem);
export const useDeleteItem = () =>
  useDatasetDraftStore((state) => state.deleteItem);
export const useBulkDeleteItems = () =>
  useDatasetDraftStore((state) => state.bulkDeleteItems);
export const useBulkEditItems = () =>
  useDatasetDraftStore((state) => state.bulkEditItems);
export const useClearDraft = () =>
  useDatasetDraftStore((state) => state.clearDraft);
export const useGetChangesSummary = () =>
  useDatasetDraftStore((state) => state.getChangesSummary);
export const useGetChangesPayload = () =>
  useDatasetDraftStore((state) => state.getChangesPayload);

export const useAddedItems = () =>
  useDatasetDraftStore((state) => state.addedItems);
export const useEditedItems = () =>
  useDatasetDraftStore((state) => state.editedItems);
export const useDeletedIds = () =>
  useDatasetDraftStore((state) => state.deletedIds);

export const useIsDraftMode = () => useDatasetDraftStore(selectIsDraftMode);

export default useDatasetDraftStore;
