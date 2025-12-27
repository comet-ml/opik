import React, { useCallback } from "react";
import ResizableSidePanel from "@/components/shared/ResizableSidePanel/ResizableSidePanel";
import { Button } from "@/components/ui/button";
import Loader from "@/components/shared/Loader/Loader";
import { useConfirmAction } from "@/components/shared/ConfirmDialog/useConfirmAction";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import { DATASET_ITEM_SOURCE, DatasetItemColumn } from "@/types/datasets";
import { useDatasetItemData } from "./DatasetItemEditor/hooks/useDatasetItemData";
import { useDatasetItemFormState } from "./DatasetItemEditor/hooks/useDatasetItemFormState";
import DatasetItemEditorForm from "./DatasetItemEditor/DatasetItemEditorForm";
import { useAddItem } from "@/store/DatasetDraftStore";

interface AddDatasetItemSidebarProps {
  open: boolean;
  setOpen: (open: boolean) => void;
  columns: DatasetItemColumn[];
}

const AddDatasetItemSidebar: React.FC<AddDatasetItemSidebarProps> = ({
  open,
  setOpen,
  columns,
}) => {
  const handleClose = useCallback(() => setOpen(false), [setOpen]);

  // Fetch dataset item data (for create mode, no datasetItemId)
  const { fields, isPending } = useDatasetItemData({
    datasetItemId: undefined,
    columns,
  });

  // Form state
  const { hasUnsavedChanges, setHasUnsavedChanges, formId } =
    useDatasetItemFormState({ datasetItemId: undefined });

  // Draft store actions
  const addItem = useAddItem();

  // Confirm dialog
  const {
    isOpen: showConfirmDialog,
    requestConfirm,
    confirm,
    cancel,
  } = useConfirmAction();

  const handleSave = useCallback(
    (data: Record<string, unknown>) => {
      addItem({
        data,
        source: DATASET_ITEM_SOURCE.manual,
        tags: [],
        created_at: new Date().toISOString(),
        last_updated_at: new Date().toISOString(),
      });
      setHasUnsavedChanges(false);
      handleClose();
    },
    [addItem, handleClose, setHasUnsavedChanges],
  );

  const handleDiscard = useCallback(() => {
    handleClose();
  }, [handleClose]);

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

  const handleCloseWithConfirm = useCallback(() => {
    requestConfirmIfNeeded(handleClose);
  }, [requestConfirmIfNeeded, handleClose]);

  return (
    <>
      <ResizableSidePanel
        panelId="add-dataset-item-sidebar"
        entity="item"
        open={open}
        onClose={handleCloseWithConfirm}
        initialWidth={0.4}
      >
        {isPending ? (
          <div className="flex size-full items-center justify-center">
            <Loader />
          </div>
        ) : (
          <div className="relative size-full overflow-y-auto p-6 pt-4">
            <div className="border-b pb-4">
              <div className="flex items-center justify-between gap-2">
                <div className="comet-title-accented">Add dataset item</div>
                <div className="flex items-center gap-2">
                  <Button
                    type="submit"
                    form={formId}
                    variant="default"
                    size="sm"
                  >
                    Save changes
                  </Button>
                  <Button variant="outline" size="sm" onClick={handleDiscard}>
                    Cancel
                  </Button>
                </div>
              </div>
            </div>
            <DatasetItemEditorForm
              formId={formId}
              fields={fields}
              onSubmit={handleSave}
              setHasUnsavedChanges={setHasUnsavedChanges}
            />
          </div>
        )}
      </ResizableSidePanel>
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
    </>
  );
};

export default AddDatasetItemSidebar;
