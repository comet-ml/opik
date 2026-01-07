import React, {
  createContext,
  useContext,
  useCallback,
  useState,
  useMemo,
} from "react";
import { useConfirmAction } from "@/components/shared/ConfirmDialog/useConfirmAction";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import {
  DATASET_ITEM_SOURCE,
  DatasetItem,
  DatasetItemColumn,
} from "@/types/datasets";
import useDatasetItemUpdateMutation from "@/api/datasets/useDatasetItemUpdateMutation";
import useDatasetItemBatchMutation from "@/api/datasets/useDatasetItemBatchMutation";
import useDatasetItemBatchDeleteMutation from "@/api/datasets/useDatasetItemBatchDeleteMutation";
import useAppStore from "@/store/AppStore";
import { DatasetField } from "./hooks/useDatasetItemData";
import { useDatasetItemNavigation } from "./hooks/useDatasetItemNavigation";
import { useDatasetItemData } from "./hooks/useDatasetItemData";
import { prepareFormDataForSave } from "./hooks/useDatasetItemFormHelpers";

interface DatasetItemEditorContextValue {
  // Data
  fields: DatasetField[];
  datasetItem: DatasetItem | undefined;
  isPending: boolean;
  tags: string[];

  // State
  isEditing: boolean;
  hasUnsavedChanges: boolean;
  isSubmitting: boolean;
  resetKey: number;
  setHasUnsavedChanges: (value: boolean) => void;

  // Methods
  handleEdit: () => void;
  handleSave: (data: Record<string, unknown>) => void;
  handleDiscard: () => void;
  handleAddTag: (tag: string) => void;
  handleDeleteTag: (tag: string) => void;
  handleDelete: (onSuccess: () => void) => void;
  requestConfirmIfNeeded: (action: () => void) => void;

  // Form
  formId: string;

  // Navigation
  horizontalNavigation: {
    hasPrevious: boolean;
    hasNext: boolean;
    onChange: (shift: 1 | -1) => void;
  };
}

const DatasetItemEditorContext = createContext<
  DatasetItemEditorContextValue | undefined
>(undefined);

interface DatasetItemEditorProviderProps {
  datasetItemId?: string;
  datasetId: string;
  columns: DatasetItemColumn[];
  rows?: DatasetItem[];
  setActiveRowId?: (id: string) => void;
  children: React.ReactNode;
  mode?: "edit" | "create";
  onClose?: () => void;
}

export const DatasetItemEditorProvider: React.FC<
  DatasetItemEditorProviderProps
> = ({
  datasetItemId,
  datasetId,
  columns,
  rows = [],
  setActiveRowId = () => {},
  children,
  mode = "edit",
  onClose,
}) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const [isEditing, setIsEditing] = useState(mode === "create");
  const [hasUnsavedChanges, setHasUnsavedChanges] = useState(false);
  const [resetKey, setResetKey] = useState(0);
  const updateMutation = useDatasetItemUpdateMutation();
  const { mutate: updateDatasetItem } = updateMutation;
  const createMutation = useDatasetItemBatchMutation();
  const { mutate: createDatasetItem } = createMutation;
  const deleteMutation = useDatasetItemBatchDeleteMutation();

  // Fetch dataset item data and parse fields
  const { fields, datasetItem, isPending } = useDatasetItemData({
    datasetItemId,
    columns,
  });

  const tags = useMemo(() => datasetItem?.tags || [], [datasetItem?.tags]);

  // Confirm dialog
  const {
    isOpen: showConfirmDialog,
    requestConfirm,
    confirm,
    cancel,
  } = useConfirmAction();

  const formId = `dataset-item-editor-form-${datasetItemId || "new"}`;

  // Handlers
  const handleEdit = useCallback(() => {
    setIsEditing(true);
  }, []);

  const handleSave = useCallback(
    (data: Record<string, unknown>) => {
      const preparedData = prepareFormDataForSave(data, fields);

      if (mode === "create") {
        createDatasetItem(
          {
            datasetId,
            datasetItems: [
              {
                data: preparedData,
                source: DATASET_ITEM_SOURCE.manual,
              },
            ],
            workspaceName,
          },
          {
            onSuccess: () => {
              setHasUnsavedChanges(false);
              if (onClose) onClose();
            },
          },
        );
        return;
      }

      if (datasetItemId) {
        updateDatasetItem(
          {
            datasetId,
            itemId: datasetItemId,
            item: { data: preparedData },
          },
          {
            onSuccess: () => {
              setIsEditing(false);
              setHasUnsavedChanges(false);
            },
          },
        );
      }
    },
    [
      updateDatasetItem,
      createDatasetItem,
      datasetId,
      datasetItemId,
      mode,
      workspaceName,
      onClose,
      fields,
    ],
  );

  const handleDiscard = useCallback(() => {
    if (mode === "create") {
      if (onClose) onClose();
      return;
    }
    setIsEditing(false);
    setHasUnsavedChanges(false);
    setResetKey((prev) => prev + 1);
  }, [mode, onClose]);

  const handleAddTag = useCallback(
    (newTag: string) => {
      if (!datasetItemId) return;
      updateDatasetItem({
        datasetId,
        itemId: datasetItemId,
        item: { tags: [...tags, newTag] },
      });
    },
    [updateDatasetItem, datasetId, datasetItemId, tags],
  );

  const handleDeleteTag = useCallback(
    (tag: string) => {
      if (!datasetItemId) return;
      updateDatasetItem({
        datasetId,
        itemId: datasetItemId,
        item: { tags: tags.filter((t) => t !== tag) },
      });
    },
    [updateDatasetItem, datasetId, datasetItemId, tags],
  );

  const handleDelete = useCallback(
    (onSuccess: () => void) => {
      if (!datasetItemId) return;
      deleteMutation.mutate(
        { datasetId, ids: [datasetItemId] },
        {
          onSuccess,
        },
      );
    },
    [deleteMutation, datasetId, datasetItemId],
  );

  const requestConfirmIfNeeded = useCallback(
    (action: () => void) => {
      if (hasUnsavedChanges) {
        requestConfirm(() => {
          action();
          if (mode === "edit") {
            setIsEditing(false);
            setResetKey((prev) => prev + 1);
          }
          setHasUnsavedChanges(false);
        });
      } else {
        action();
        if (mode === "edit") {
          setIsEditing(false);
        }
      }
    },
    [hasUnsavedChanges, requestConfirm, mode],
  );

  // Navigation
  const { horizontalNavigation } = useDatasetItemNavigation({
    activeRowId: datasetItemId || "",
    rows,
    setActiveRowId,
    checkUnsavedChanges: requestConfirmIfNeeded,
  });

  const handleDialogOpenChange = useCallback(
    (open: boolean) => {
      if (!open) {
        cancel();
      }
    },
    [cancel],
  );

  const contextValue: DatasetItemEditorContextValue = useMemo(
    () => ({
      fields,
      datasetItem,
      isPending,
      tags,
      isEditing,
      hasUnsavedChanges,
      isSubmitting: updateMutation.isPending || createMutation.isPending,
      resetKey,
      setHasUnsavedChanges,
      handleEdit,
      handleSave,
      handleDiscard,
      handleAddTag,
      handleDeleteTag,
      handleDelete,
      requestConfirmIfNeeded,
      formId,
      horizontalNavigation,
    }),
    [
      fields,
      datasetItem,
      isPending,
      tags,
      isEditing,
      hasUnsavedChanges,
      updateMutation.isPending,
      createMutation.isPending,
      resetKey,
      handleEdit,
      handleSave,
      handleDiscard,
      handleAddTag,
      handleDeleteTag,
      handleDelete,
      requestConfirmIfNeeded,
      formId,
      horizontalNavigation,
    ],
  );

  return (
    <DatasetItemEditorContext.Provider value={contextValue}>
      {children}
      <ConfirmDialog
        open={showConfirmDialog}
        setOpen={handleDialogOpenChange}
        onConfirm={cancel}
        onCancel={confirm}
        title="Discard changes?"
        description={
          mode === "create"
            ? "You have unsaved changes. Do you want to discard them and close?"
            : "You made some changes that haven't been saved yet. Do you want to keep editing or discard them?"
        }
        confirmText="Keep editing"
        cancelText="Discard changes"
        confirmButtonVariant="default"
      />
    </DatasetItemEditorContext.Provider>
  );
};

export const useDatasetItemEditorContext =
  (): DatasetItemEditorContextValue => {
    const context = useContext(DatasetItemEditorContext);
    if (!context) {
      throw new Error(
        "useDatasetItemEditorContext must be used within DatasetItemEditorProvider",
      );
    }
    return context;
  };
