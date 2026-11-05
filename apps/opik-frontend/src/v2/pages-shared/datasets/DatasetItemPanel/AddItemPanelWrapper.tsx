import React, { ReactNode, useEffect, useState } from "react";

import ResizableSidePanel from "@/shared/ResizableSidePanel/ResizableSidePanel";
import ResizableSidePanelTopBar from "@/shared/ResizableSidePanel/ResizableSidePanelTopBar";
import { Button } from "@/ui/button";
import TagListRenderer from "@/shared/TagListRenderer/TagListRenderer";
import { useConfirmAction } from "@/shared/ConfirmDialog/useConfirmAction";
import ConfirmDialog from "@/shared/ConfirmDialog/ConfirmDialog";

export interface AddItemPanelContext {
  tags: string[];
  setHasUnsavedChanges: (v: boolean) => void;
  requestClose: () => void;
}

interface AddItemPanelWrapperProps {
  panelId: string;
  // Omit to let children render their own footer via `requestClose`.
  formId?: string;
  title: string;
  open: boolean;
  onClose: () => void;
  initialWidth?: number;
  showAddTagLabel?: boolean;
  children: (context: AddItemPanelContext) => ReactNode;
}

const AddItemPanelWrapperContent: React.FC<{
  formId?: string;
  showAddTagLabel?: boolean;
  onClose: () => void;
  onDirtyChange: (dirty: boolean) => void;
  children: (context: AddItemPanelContext) => ReactNode;
}> = ({ formId, showAddTagLabel, onClose, onDirtyChange, children }) => {
  const [tags, setTags] = useState<string[]>([]);
  const [hasUnsavedChanges, setHasUnsavedChanges] = useState(false);
  const isDirty = hasUnsavedChanges || tags.length > 0;

  useEffect(() => {
    onDirtyChange(isDirty);
  }, [isDirty, onDirtyChange]);

  const {
    isOpen: showConfirmDialog,
    requestConfirm,
    confirm,
    cancel,
  } = useConfirmAction();

  const requestClose = () => {
    if (isDirty) {
      requestConfirm(onClose);
    } else {
      onClose();
    }
  };

  const context: AddItemPanelContext = {
    tags,
    setHasUnsavedChanges,
    requestClose,
  };

  return (
    <>
      <div className="flex size-full flex-col">
        <div className="shrink-0 border-b bg-background px-6 py-4">
          <TagListRenderer
            tags={tags}
            onAddTag={(tag) => setTags((prev) => [...prev, tag])}
            onDeleteTag={(tag) =>
              setTags((prev) => prev.filter((t) => t !== tag))
            }
            size="sm"
            align="start"
            showAddLabelWhenEmpty={showAddTagLabel}
          />
        </div>

        {formId ? (
          <>
            <div className="flex-1 overflow-y-auto">{children(context)}</div>

            <div className="flex shrink-0 items-center justify-end gap-2 border-t px-6 py-4">
              <Button variant="outline" size="sm" onClick={requestClose}>
                Cancel
              </Button>
              <Button variant="default" size="sm" type="submit" form={formId}>
                Save changes
              </Button>
            </div>
          </>
        ) : (
          <div className="flex min-h-0 flex-1 flex-col">
            {children(context)}
          </div>
        )}
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
  title,
  open,
  onClose,
  initialWidth,
  showAddTagLabel,
  children,
}) => {
  const [isDirty, setIsDirty] = useState(false);

  return (
    <ResizableSidePanel
      panelId={panelId}
      open={open}
      onClose={onClose}
      initialWidth={initialWidth}
      blockOverlayClose={isDirty}
      header={
        <ResizableSidePanelTopBar
          variant="form"
          title={title}
          onClose={onClose}
        />
      }
    >
      {open && (
        <AddItemPanelWrapperContent
          formId={formId}
          onClose={onClose}
          showAddTagLabel={showAddTagLabel}
          onDirtyChange={setIsDirty}
        >
          {children}
        </AddItemPanelWrapperContent>
      )}
    </ResizableSidePanel>
  );
};

export default AddItemPanelWrapper;
