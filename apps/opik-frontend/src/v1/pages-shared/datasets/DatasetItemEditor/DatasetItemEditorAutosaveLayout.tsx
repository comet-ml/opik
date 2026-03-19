import React, { useCallback, useMemo } from "react";
import { Copy, MoreHorizontal, Share, Trash } from "lucide-react";
import copy from "clipboard-copy";
import ResizableSidePanel from "@/shared/ResizableSidePanel/ResizableSidePanel";
import { Button } from "@/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/ui/dropdown-menu";
import { useToast } from "@/ui/use-toast";
import Loader from "@/shared/Loader/Loader";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import TagListRenderer from "@/shared/TagListRenderer/TagListRenderer";
import { processInputData } from "@/lib/images";
import ImagesListWrapper from "@/shared/attachments/ImagesListWrapper/ImagesListWrapper";
import { useDatasetItemEditorAutosaveContext } from "./DatasetItemEditorAutosaveContext";
import DatasetItemEditorForm from "./DatasetItemEditorForm";

interface DatasetItemEditorAutosaveLayoutProps {
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

const DatasetItemEditorAutosaveLayout: React.FC<
  DatasetItemEditorAutosaveLayoutProps
> = ({ datasetItemId, isOpen, onClose }) => {
  const {
    isPending,
    handleFieldChange,
    handleDelete,
    tags,
    handleAddTag,
    handleDeleteTag,
    fields,
    formId,
    horizontalNavigation,
    datasetItem,
  } = useDatasetItemEditorAutosaveContext();

  const { toast } = useToast();

  const { media } = useMemo(
    () => processInputData(datasetItem?.data),
    [datasetItem?.data],
  );

  const hasMedia = media.length > 0;

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

  const handleClose = useCallback(() => {
    onClose();
  }, [onClose]);

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
          onDelete={handleDeleteItemConfirm}
        />
      }
      onClose={handleClose}
      horizontalNavigation={horizontalNavigation}
    >
      {isPending ? (
        <div className="flex size-full items-center justify-center">
          <Loader />
        </div>
      ) : (
        <div className="relative size-full overflow-y-auto">
          <div className="sticky top-0 z-10 border-b bg-background p-6 pb-4">
            <TooltipWrapper content={datasetItemId}>
              <div className="comet-body-accented">
                Dataset item{" "}
                <span className="comet-body-s text-muted-slate">
                  {truncateId(datasetItemId)}
                </span>
              </div>
            </TooltipWrapper>
            <TagListRenderer
              tags={tags}
              onAddTag={handleAddTag}
              onDeleteTag={handleDeleteTag}
              size="sm"
              align="start"
            />
          </div>
          {hasMedia && (
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
              onFieldChange={handleFieldChange}
            />
          </div>
        </div>
      )}
    </ResizableSidePanel>
  );
};

export default DatasetItemEditorAutosaveLayout;
