import React, { useCallback } from "react";
import { Pencil } from "lucide-react";
import ResizableSidePanel from "@/components/shared/ResizableSidePanel/ResizableSidePanel";
import { Button } from "@/components/ui/button";
import Loader from "@/components/shared/Loader/Loader";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import TagListRenderer from "@/components/shared/TagListRenderer/TagListRenderer";
import { useDatasetItemEditorContext } from "./DatasetItemEditorContext";
import DatasetItemEditorForm from "./DatasetItemEditorForm";

interface DatasetItemEditorLayoutProps {
  datasetItemId: string;
  isOpen: boolean;
  onClose: () => void;
}

const truncateId = (id: string): string => {
  if (id.length <= 12) return id;
  return `${id.slice(0, 4)}...${id.slice(-4)}`;
};

const DatasetItemEditorLayout: React.FC<DatasetItemEditorLayoutProps> = ({
  datasetItemId,
  isOpen,
  onClose,
}) => {
  const {
    isPending,
    isEditing,
    hasUnsavedChanges,
    isSubmitting,
    handleEdit,
    handleDiscard,
    requestConfirmIfNeeded,
    horizontalNavigation,
    tags,
    handleAddTag,
    handleDeleteTag,
    fields,
    handleSave,
    formId,
    setHasUnsavedChanges,
    resetKey,
  } = useDatasetItemEditorContext();

  const handleCloseWithConfirm = useCallback(() => {
    requestConfirmIfNeeded(onClose);
  }, [requestConfirmIfNeeded, onClose]);

  return (
    <ResizableSidePanel
      panelId="dataset-item-editor"
      entity="item"
      open={isOpen}
      onClose={handleCloseWithConfirm}
      horizontalNavigation={horizontalNavigation}
    >
      {isPending ? (
        <div className="flex size-full items-center justify-center">
          <Loader />
        </div>
      ) : (
        <div className="relative size-full overflow-y-auto">
          <div className="sticky top-0 z-10 border-b bg-background p-6 pb-4">
            <div className="flex items-center justify-between gap-2">
              <TooltipWrapper content={datasetItemId}>
                <div className="comet-title-xs">
                  Dataset item{" "}
                  <span className="comet-body-s text-muted-slate">
                    {truncateId(datasetItemId)}
                  </span>
                </div>
              </TooltipWrapper>
              <div className="flex items-center gap-2">
                {!isEditing && (
                  <Button variant="outline" size="sm" onClick={handleEdit}>
                    <Pencil className="mr-2 size-4" />
                    Edit
                  </Button>
                )}
                {isEditing && (
                  <>
                    <Button
                      type="submit"
                      form={formId}
                      variant="default"
                      size="sm"
                      disabled={!hasUnsavedChanges || isSubmitting}
                    >
                      {isSubmitting ? "Saving..." : "Save changes"}
                    </Button>
                    <Button variant="outline" size="sm" onClick={handleDiscard}>
                      Cancel
                    </Button>
                  </>
                )}
              </div>
            </div>
            <TagListRenderer
              tags={tags}
              onAddTag={handleAddTag}
              onDeleteTag={handleDeleteTag}
              size="sm"
              align="start"
            />
          </div>
          <div className="p-6 pt-4">
            <DatasetItemEditorForm
              key={datasetItemId}
              formId={formId}
              fields={fields}
              isEditing={isEditing}
              onSubmit={handleSave}
              setHasUnsavedChanges={setHasUnsavedChanges}
              resetKey={resetKey}
            />
          </div>
        </div>
      )}
    </ResizableSidePanel>
  );
};

export default DatasetItemEditorLayout;
