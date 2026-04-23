import React, { ReactNode, useState } from "react";

import ResizableSidePanel from "@/shared/ResizableSidePanel/ResizableSidePanel";
import { Button } from "@/ui/button";
import TagListRenderer from "@/shared/TagListRenderer/TagListRenderer";
import { useConfirmAction } from "@/shared/ConfirmDialog/useConfirmAction";
import ConfirmDialog from "@/shared/ConfirmDialog/ConfirmDialog";

export interface AddItemPanelContext {
  tags: string[];
  setHasUnsavedChanges: (v: boolean) => void;
}

interface AddItemPanelWrapperProps {
  panelId: string;
  formId: string;
  open: boolean;
  onClose: () => void;
  initialWidth?: number;
  children: (context: AddItemPanelContext) => ReactNode;
}

const AddItemPanelWrapperContent: React.FC<{
  formId: string;
  onClose: () => void;
  children: (context: AddItemPanelContext) => ReactNode;
}> = ({ formId, onClose, children }) => {
  const [tags, setTags] = useState<string[]>([]);
  const [hasUnsavedChanges, setHasUnsavedChanges] = useState(false);
  const isDirty = hasUnsavedChanges || tags.length > 0;

  const {
    isOpen: showConfirmDialog,
    requestConfirm,
    confirm,
    cancel,
  } = useConfirmAction();

  return (
    <>
      <div className="flex size-full flex-col">
        <div className="shrink-0 border-b bg-background p-6 pb-4">
          <div className="comet-body-accented">Add item</div>
          <TagListRenderer
            tags={tags}
            onAddTag={(tag) => setTags((prev) => [...prev, tag])}
            onDeleteTag={(tag) =>
              setTags((prev) => prev.filter((t) => t !== tag))
            }
            size="sm"
            align="start"
          />
        </div>

        <div className="flex-1 overflow-y-auto">
          {children({ tags, setHasUnsavedChanges })}
        </div>

        <div className="flex shrink-0 items-center justify-end gap-2 border-t px-6 py-4">
          <Button
            variant="outline"
            size="sm"
            onClick={() => {
              if (isDirty) {
                requestConfirm(onClose);
              } else {
                onClose();
              }
            }}
          >
            Cancel
          </Button>
          <Button variant="default" size="sm" type="submit" form={formId}>
            Save changes
          </Button>
        </div>
      </div>

      <ConfirmDialog
        open={showConfirmDialog}
        setOpen={() => cancel()}
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

const AddItemPanelWrapper: React.FC<AddItemPanelWrapperProps> = ({
  panelId,
  formId,
  open,
  onClose,
  initialWidth,
  children,
}) => {
  return (
    <ResizableSidePanel
      panelId={panelId}
      entity="item"
      open={open}
      onClose={onClose}
      initialWidth={initialWidth}
    >
      {open && (
        <AddItemPanelWrapperContent formId={formId} onClose={onClose}>
          {children}
        </AddItemPanelWrapperContent>
      )}
    </ResizableSidePanel>
  );
};

export default AddItemPanelWrapper;
