import React, { createContext, useContext, useCallback, useMemo } from "react";
import { DatasetItem, DatasetItemColumn } from "@/types/datasets";
import useDatasetItemUpdateMutation from "@/api/datasets/useDatasetItemUpdateMutation";
import useDatasetItemBatchDeleteMutation from "@/api/datasets/useDatasetItemBatchDeleteMutation";
import { DatasetField } from "./hooks/useDatasetItemData";
import { useDatasetItemNavigation } from "./hooks/useDatasetItemNavigation";
import { useDatasetItemData } from "./hooks/useDatasetItemData";
import { useDatasetItemFormState } from "./hooks/useDatasetItemFormState";
import { useAutosave } from "./hooks/useAutosave";

interface DatasetItemEditorAutosaveContextValue {
  // Data
  fields: DatasetField[];
  datasetItem: DatasetItem | undefined;
  isPending: boolean;
  tags: string[];
  datasetId: string;

  // Autosave state
  isAutoSaving: boolean;
  lastSavedAt: Date | null;
  hasError: boolean;

  // Methods
  handleFieldChange: (data: Record<string, unknown>) => void;
  handleAddTag: (tag: string) => void;
  handleDeleteTag: (tag: string) => void;
  handleDelete: (onSuccess: () => void) => void;
  flushPendingSave: () => void;
  resetSaveState: () => void;

  // Form
  formId: string;

  // Navigation
  horizontalNavigation: {
    hasPrevious: boolean;
    hasNext: boolean;
    onChange: (shift: 1 | -1) => void;
  };
}

const DatasetItemEditorAutosaveContext = createContext<
  DatasetItemEditorAutosaveContextValue | undefined
>(undefined);

interface DatasetItemEditorAutosaveProviderProps {
  datasetItemId?: string;
  datasetId: string;
  columns: DatasetItemColumn[];
  rows?: DatasetItem[];
  setActiveRowId?: (id: string) => void;
  children: React.ReactNode;
}

export const DatasetItemEditorAutosaveProvider: React.FC<
  DatasetItemEditorAutosaveProviderProps
> = ({
  datasetItemId,
  datasetId,
  columns,
  rows = [],
  setActiveRowId = () => {},
  children,
}) => {
  // Fetch dataset item data and parse fields
  const { fields, datasetItem, isPending } = useDatasetItemData({
    datasetItemId,
    columns,
  });

  const tags = useMemo(() => datasetItem?.tags || [], [datasetItem?.tags]);

  // Form state
  const { formId } = useDatasetItemFormState({ datasetItemId });

  // Mutations
  const updateMutation = useDatasetItemUpdateMutation();
  const deleteMutation = useDatasetItemBatchDeleteMutation();

  // Autosave
  const {
    handleAutosave,
    isAutoSaving,
    lastSavedAt,
    hasError,
    cancelPendingSave,
    flushPendingSave,
    resetSaveState,
  } = useAutosave({
    datasetId,
    datasetItemId,
    debounceMs: 1000,
  });

  // Tag handlers
  const handleAddTag = useCallback(
    (newTag: string) => {
      if (!datasetItemId) return;
      updateMutation.mutate({
        datasetId,
        itemId: datasetItemId,
        item: { tags: [...tags, newTag] },
      });
    },
    [updateMutation, datasetId, datasetItemId, tags],
  );

  const handleDeleteTag = useCallback(
    (tag: string) => {
      if (!datasetItemId) return;
      updateMutation.mutate({
        datasetId,
        itemId: datasetItemId,
        item: { tags: tags.filter((t) => t !== tag) },
      });
    },
    [updateMutation, datasetId, datasetItemId, tags],
  );

  const handleFieldChange = useCallback(
    (data: Record<string, unknown>) => {
      handleAutosave(data);
    },
    [handleAutosave],
  );

  const handleDelete = useCallback(
    (onSuccess: () => void) => {
      if (!datasetItemId) return;
      // Cancel any pending autosave before deleting
      cancelPendingSave();
      deleteMutation.mutate(
        { datasetId, ids: [datasetItemId] },
        {
          onSuccess,
        },
      );
    },
    [deleteMutation, datasetId, datasetItemId, cancelPendingSave],
  );

  // Navigation - no unsaved changes confirmation needed for autosave
  const handleBeforeNavigate = useCallback(() => {
    flushPendingSave();
    resetSaveState();
  }, [flushPendingSave, resetSaveState]);

  const { horizontalNavigation } = useDatasetItemNavigation({
    activeRowId: datasetItemId || "",
    rows,
    setActiveRowId,
    checkUnsavedChanges: (action: () => void) => action(), // No confirmation needed
    onBeforeNavigate: handleBeforeNavigate,
  });

  const contextValue: DatasetItemEditorAutosaveContextValue = useMemo(
    () => ({
      fields,
      datasetItem,
      isPending,
      tags,
      datasetId,
      isAutoSaving,
      lastSavedAt,
      hasError,
      handleFieldChange,
      handleAddTag,
      handleDeleteTag,
      handleDelete,
      flushPendingSave,
      resetSaveState,
      formId,
      horizontalNavigation,
    }),
    [
      fields,
      datasetItem,
      isPending,
      tags,
      datasetId,
      isAutoSaving,
      lastSavedAt,
      hasError,
      handleFieldChange,
      handleAddTag,
      handleDeleteTag,
      handleDelete,
      flushPendingSave,
      resetSaveState,
      formId,
      horizontalNavigation,
    ],
  );

  return (
    <DatasetItemEditorAutosaveContext.Provider value={contextValue}>
      {children}
    </DatasetItemEditorAutosaveContext.Provider>
  );
};

export const useDatasetItemEditorAutosaveContext =
  (): DatasetItemEditorAutosaveContextValue => {
    const context = useContext(DatasetItemEditorAutosaveContext);
    if (!context) {
      throw new Error(
        "useDatasetItemEditorAutosaveContext must be used within DatasetItemEditorAutosaveProvider",
      );
    }
    return context;
  };
