import React, { useCallback } from "react";
import ResizableSidePanel from "@/components/shared/ResizableSidePanel/ResizableSidePanel";
import { Button } from "@/components/ui/button";
import Loader from "@/components/shared/Loader/Loader";
import { useDatasetItemEditorContext } from "./DatasetItemEditorContext";
import DatasetItemEditorForm from "./DatasetItemEditorForm";

interface AddDatasetItemSidebarLayoutProps {
  isOpen: boolean;
  onClose: () => void;
}

const AddDatasetItemSidebarLayout: React.FC<
  AddDatasetItemSidebarLayoutProps
> = ({ isOpen, onClose }) => {
  const {
    isPending,
    isEditing,
    isSubmitting,
    handleDiscard,
    requestConfirmIfNeeded,
    fields,
    handleSave,
    setHasUnsavedChanges,
    resetKey,
    formId,
  } = useDatasetItemEditorContext();

  const handleCloseWithConfirm = useCallback(() => {
    requestConfirmIfNeeded(onClose);
  }, [requestConfirmIfNeeded, onClose]);

  return (
    <ResizableSidePanel
      panelId="add-dataset-item-sidebar"
      entity="item"
      open={isOpen}
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
                  disabled={isSubmitting}
                >
                  {isSubmitting ? "Saving..." : "Save changes"}
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
            isEditing={isEditing}
            onSubmit={handleSave}
            setHasUnsavedChanges={setHasUnsavedChanges}
            resetKey={resetKey}
          />
        </div>
      )}
    </ResizableSidePanel>
  );
};

export default AddDatasetItemSidebarLayout;
