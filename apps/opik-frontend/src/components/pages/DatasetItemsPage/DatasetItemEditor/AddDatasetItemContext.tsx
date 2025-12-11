import React, { createContext, useContext, useCallback, useMemo } from "react";
import { useConfirmAction } from "@/components/shared/ConfirmDialog/useConfirmAction";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import { DATASET_ITEM_SOURCE, DatasetItemColumn } from "@/types/datasets";
import useDatasetItemBatchMutation from "@/api/datasets/useDatasetItemBatchMutation";
import useAppStore from "@/store/AppStore";
import { DatasetField } from "./hooks/useDatasetItemData";
import { useDatasetItemData } from "./hooks/useDatasetItemData";
import { useDatasetItemFormState } from "./hooks/useDatasetItemFormState";

interface AddDatasetItemContextValue {
  // Data
  fields: DatasetField[];
  isPending: boolean;
  datasetId: string;

  // State
  hasUnsavedChanges: boolean;
  isSubmitting: boolean;
  setHasUnsavedChanges: (value: boolean) => void;

  // Methods
  handleSave: (data: Record<string, unknown>) => void;
  handleDiscard: () => void;
  requestConfirmIfNeeded: (action: () => void) => void;

  // Form
  formId: string;
}

const AddDatasetItemContext = createContext<
  AddDatasetItemContextValue | undefined
>(undefined);

interface AddDatasetItemProviderProps {
  datasetId: string;
  columns: DatasetItemColumn[];
  children: React.ReactNode;
  onClose?: () => void;
}

export const AddDatasetItemProvider: React.FC<AddDatasetItemProviderProps> = ({
  datasetId,
  columns,
  children,
  onClose,
}) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  // Fetch dataset item data (for create mode, no datasetItemId)
  const { fields, isPending } = useDatasetItemData({
    datasetItemId: undefined,
    columns,
  });

  // Form state
  const { hasUnsavedChanges, setHasUnsavedChanges, formId } =
    useDatasetItemFormState({ datasetItemId: undefined });

  // Mutations
  const createMutation = useDatasetItemBatchMutation();

  // Confirm dialog
  const {
    isOpen: showConfirmDialog,
    requestConfirm,
    confirm,
    cancel,
  } = useConfirmAction();

  const handleSave = useCallback(
    (data: Record<string, unknown>) => {
      createMutation.mutate(
        {
          datasetId,
          datasetItems: [
            {
              data,
              source: DATASET_ITEM_SOURCE.manual,
            },
          ],
          workspaceName,
        },
        {
          onSuccess: () => {
            setHasUnsavedChanges(false);
            onClose?.();
          },
        },
      );
    },
    [createMutation, datasetId, workspaceName, onClose, setHasUnsavedChanges],
  );

  const handleDiscard = useCallback(() => {
    onClose?.();
  }, [onClose]);

  const requestConfirmIfNeeded = useCallback(
    (action: () => void) => {
      if (hasUnsavedChanges) {
        requestConfirm(() => {
          action();
          setHasUnsavedChanges(false);
        });
      } else {
        action();
      }
    },
    [hasUnsavedChanges, requestConfirm, setHasUnsavedChanges],
  );

  const handleDialogOpenChange = useCallback(
    (open: boolean) => {
      if (!open) {
        cancel();
      }
    },
    [cancel],
  );

  const contextValue: AddDatasetItemContextValue = useMemo(
    () => ({
      fields,
      isPending,
      datasetId,
      hasUnsavedChanges,
      isSubmitting: createMutation.isPending,
      setHasUnsavedChanges,
      handleSave,
      handleDiscard,
      requestConfirmIfNeeded,
      formId,
    }),
    [
      fields,
      isPending,
      datasetId,
      hasUnsavedChanges,
      createMutation.isPending,
      handleSave,
      handleDiscard,
      requestConfirmIfNeeded,
      setHasUnsavedChanges,
      formId,
    ],
  );

  return (
    <AddDatasetItemContext.Provider value={contextValue}>
      {children}
      <ConfirmDialog
        open={showConfirmDialog}
        setOpen={handleDialogOpenChange}
        onConfirm={cancel}
        onCancel={confirm}
        title="Discard changes?"
        description="You have unsaved changes. Do you want to discard them and close?"
        confirmText="Keep editing"
        cancelText="Discard changes"
        confirmButtonVariant="default"
      />
    </AddDatasetItemContext.Provider>
  );
};

export const useAddDatasetItemContext = (): AddDatasetItemContextValue => {
  const context = useContext(AddDatasetItemContext);
  if (!context) {
    throw new Error(
      "useAddDatasetItemContext must be used within AddDatasetItemProvider",
    );
  }
  return context;
};
