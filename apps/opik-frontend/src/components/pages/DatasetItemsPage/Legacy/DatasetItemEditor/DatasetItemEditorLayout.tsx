import React, { useCallback, useMemo, useState } from "react";
import { Copy, MoreHorizontal, Pencil, Share, Trash } from "lucide-react";
import copy from "clipboard-copy";
import ResizableSidePanel from "@/components/shared/ResizableSidePanel/ResizableSidePanel";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { useToast } from "@/components/ui/use-toast";
import Loader from "@/components/shared/Loader/Loader";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import TagListRenderer from "@/components/shared/TagListRenderer/TagListRenderer";
import ImagesListWrapper from "@/components/shared/attachments/ImagesListWrapper/ImagesListWrapper";
import RemoveDatasetItemsDialog from "@/components/pages/DatasetItemsPage/RemoveDatasetItemsDialog";
import { useDatasetItemDeletePreference } from "@/components/pages/DatasetItemsPage/hooks/useDatasetItemDeletePreference";
import { useDatasetItemEditorContext } from "./DatasetItemEditorContext";
import DatasetItemEditorForm from "./DatasetItemEditorForm";
import { processInputData } from "@/lib/images";

interface DatasetItemEditorLayoutProps {
  datasetItemId: string;
  isOpen: boolean;
  onClose: () => void;
}

const truncateId = (id: string): string => {
  if (id.length <= 12) return id;
  return `${id.slice(0, 4)}...${id.slice(-4)}`;
};

interface DatasetItemEditorActionsPanelProps {
  datasetItemId: string;
  onShare: () => void;
  onCopyId: () => void;
  onDelete: () => void;
}

const DatasetItemEditorActionsPanel: React.FC<
  DatasetItemEditorActionsPanelProps
> = ({ datasetItemId, onShare, onCopyId, onDelete }) => {
  return (
    <div className="flex flex-auto items-center justify-end pl-6">
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button variant="outline" size="icon-sm">
            <span className="sr-only">Actions menu</span>
            <MoreHorizontal />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" className="w-52">
          <DropdownMenuItem onClick={onShare}>
            <Share className="mr-2 size-4" />
            Share item
          </DropdownMenuItem>
          <TooltipWrapper content={datasetItemId} side="left">
            <DropdownMenuItem onClick={onCopyId}>
              <Copy className="mr-2 size-4" />
              Copy item ID
            </DropdownMenuItem>
          </TooltipWrapper>
          <DropdownMenuSeparator />
          <DropdownMenuItem onClick={onDelete}>
            <Trash className="mr-2 size-4" />
            Delete item
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
    </div>
  );
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
    handleDelete,
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
    datasetItem,
  } = useDatasetItemEditorContext();

  const { toast } = useToast();
  const [popupOpen, setPopupOpen] = useState(false);
  const [dontAskAgain] = useDatasetItemDeletePreference();

  const { media } = useMemo(
    () => processInputData(datasetItem?.data),
    [datasetItem?.data],
  );

  const hasMedia = media.length > 0;

  const handleCloseWithConfirm = useCallback(() => {
    requestConfirmIfNeeded(onClose);
  }, [requestConfirmIfNeeded, onClose]);

  const handleShare = useCallback(() => {
    toast({
      description: "URL successfully copied to clipboard",
    });
    copy(window.location.href);
  }, [toast]);

  const handleCopyId = useCallback(() => {
    toast({
      description: "Item ID successfully copied to clipboard",
    });
    copy(datasetItemId);
  }, [datasetItemId, toast]);

  const handleDeleteItemConfirm = useCallback(() => {
    handleDelete(onClose);
  }, [handleDelete, onClose]);

  const handleDeleteClick = useCallback(() => {
    if (dontAskAgain) {
      handleDeleteItemConfirm();
    } else {
      setPopupOpen(true);
    }
  }, [dontAskAgain, handleDeleteItemConfirm]);

  return (
    <ResizableSidePanel
      panelId="dataset-item-editor"
      entity="item"
      open={isOpen}
      headerContent={
        <DatasetItemEditorActionsPanel
          datasetItemId={datasetItemId}
          onShare={handleShare}
          onCopyId={handleCopyId}
          onDelete={handleDeleteClick}
        />
      }
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
                <div className="comet-body-accented">
                  {isEditing ? "Edit dataset item" : "Dataset item"}{" "}
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
          {!isEditing && hasMedia && (
            <div className="border-b px-6 py-4">
              <div className="mb-2 text-sm font-medium">Media</div>
              <ImagesListWrapper media={media} />
            </div>
          )}
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
      <RemoveDatasetItemsDialog
        open={popupOpen}
        setOpen={setPopupOpen}
        onConfirm={handleDeleteItemConfirm}
        title="Remove dataset item"
        description="The item will be deleted from your current dataset view. The changes won't take effect until you save and create a new version."
        confirmText="Remove dataset item"
      />
    </ResizableSidePanel>
  );
};

export default DatasetItemEditorLayout;
