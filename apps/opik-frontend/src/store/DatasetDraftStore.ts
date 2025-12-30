import { create } from "zustand";
import { v4 as uuidv4 } from "uuid";
import { DatasetItem } from "@/types/datasets";

interface DatasetDraftState {
  addedItems: Map<string, DatasetItem>;
  editedItems: Map<string, Partial<DatasetItem>>;
  deletedIds: Set<string>;
  isAllItemsSelected: boolean;

  addItem: (item: Omit<DatasetItem, "id">) => void;
  bulkAddItems: (items: Omit<DatasetItem, "id">[]) => void;
  editItem: (id: string, changes: Partial<DatasetItem>) => void;
  deleteItem: (id: string) => void;
  bulkDeleteItems: (ids: string[]) => void;
  bulkEditItems: (ids: string[], changes: Partial<DatasetItem>) => void;
  clearDraft: () => void;
  setIsAllItemsSelected: (value: boolean) => void;
  getChangesSummary: () => { added: number; edited: number; deleted: number };
  getChangesPayload: () => {
    addedItems: DatasetItem[];
    editedItems: Partial<DatasetItem>[];
    deletedIds: string[];
  };
}

const useDatasetDraftStore = create<DatasetDraftState>((set, get) => ({
  addedItems: new Map(),
  editedItems: new Map(),
  deletedIds: new Set(),
  isAllItemsSelected: false,

  addItem: (item: Omit<DatasetItem, "id">) => {
    const tempId = uuidv4();
    const newItem: DatasetItem = {
      ...item,
      id: tempId,
    };

    set((state) => {
      const newAddedItems = new Map(state.addedItems);
      newAddedItems.set(tempId, newItem);
      return { addedItems: newAddedItems, isAllItemsSelected: false };
    });
  },

  bulkAddItems: (items: Omit<DatasetItem, "id">[]) => {
    set((state) => {
      const newAddedItems = new Map(state.addedItems);

      items.forEach((item) => {
        const tempId = uuidv4();
        const newItem: DatasetItem = {
          ...item,
          id: tempId,
        };
        newAddedItems.set(tempId, newItem);
      });

      return { addedItems: newAddedItems, isAllItemsSelected: false };
    });
  },

  editItem: (id: string, changes: Partial<DatasetItem>) => {
    set((state) => {
      if (state.addedItems.has(id)) {
        const newAddedItems = new Map(state.addedItems);
        const existingItem = state.addedItems.get(id)!;
        newAddedItems.set(id, { ...existingItem, ...changes });
        return { addedItems: newAddedItems, isAllItemsSelected: false };
      }

      const newEditedItems = new Map(state.editedItems);
      const existingChanges = state.editedItems.get(id) || {};
      newEditedItems.set(id, { ...existingChanges, ...changes });
      return { editedItems: newEditedItems, isAllItemsSelected: false };
    });
  },

  deleteItem: (id: string) => {
    get().bulkDeleteItems([id]);
  },

  bulkDeleteItems: (ids: string[]) => {
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

  bulkEditItems: (ids: string[], changes: Partial<DatasetItem>) => {
    set((state) => {
      const newAddedItems = new Map(state.addedItems);
      const newEditedItems = new Map(state.editedItems);

      ids.forEach((id) => {
        if (state.addedItems.has(id)) {
          const existingItem = state.addedItems.get(id)!;
          newAddedItems.set(id, { ...existingItem, ...changes });
        } else {
          const existingChanges = state.editedItems.get(id) || {};
          newEditedItems.set(id, { ...existingChanges, ...changes });
        }
      });

      return {
        addedItems: newAddedItems,
        editedItems: newEditedItems,
        isAllItemsSelected: false,
      };
    });
  },

  clearDraft: () => {
    set({
      addedItems: new Map(),
      editedItems: new Map(),
      deletedIds: new Set(),
      isAllItemsSelected: false,
    });
  },

  setIsAllItemsSelected: (value: boolean) => {
    set({ isAllItemsSelected: value });
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
        ([id, changes]) => ({
          id,
          ...changes,
        }),
      ),
      deletedIds: Array.from(state.deletedIds),
    };
  },
}));

export const selectIsDraftMode = (state: DatasetDraftState) =>
  state.addedItems.size > 0 ||
  state.editedItems.size > 0 ||
  state.deletedIds.size > 0;

export const selectAddedItemById = (id: string) => (state: DatasetDraftState) =>
  state.addedItems.get(id);

export const useAddItem = () => useDatasetDraftStore((state) => state.addItem);
export const useBulkAddItems = () =>
  useDatasetDraftStore((state) => state.bulkAddItems);
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

export const useAddedDatasetItemById = (id?: string) =>
  useDatasetDraftStore((state) => state.addedItems.get(id || ""));
export const useEditedDatasetItemById = (id?: string) =>
  useDatasetDraftStore((state) => state.editedItems.get(id || ""));
export const useIsDraftMode = () => useDatasetDraftStore(selectIsDraftMode);

export const useIsAllItemsSelected = () =>
  useDatasetDraftStore((state) => state.isAllItemsSelected);
export const useSetIsAllItemsSelected = () =>
  useDatasetDraftStore((state) => state.setIsAllItemsSelected);

export default useDatasetDraftStore;
