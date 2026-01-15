import React, { useState } from "react";
import { DatasetItem } from "@/types/datasets";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogFooter,
} from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { useToast } from "@/components/ui/use-toast";
import useDatasetItemBatchUpdateMutation from "@/api/datasets/useDatasetItemBatchUpdateMutation";
import { Filters } from "@/types/filters";
import {
  useBulkEditItems,
  useIsAllItemsSelected,
} from "@/store/DatasetDraftStore";
import { generateBatchGroupId } from "@/lib/utils";

type AddTagDialogProps = {
  datasetId: string;
  rows: Array<DatasetItem>;
  open: boolean;
  setOpen: (open: boolean) => void;
  onSuccess?: () => void;
  filters?: Filters;
  search?: string;
  totalCount?: number;
};

const AddTagDialog: React.FunctionComponent<AddTagDialogProps> = ({
  datasetId,
  rows,
  open,
  setOpen,
  onSuccess,
  filters = [],
  search = "",
  totalCount = 0,
}) => {
  const { toast } = useToast();
  const [newTag, setNewTag] = useState<string>("");
  const batchUpdateMutation = useDatasetItemBatchUpdateMutation();
  const bulkEditItems = useBulkEditItems();
  const isAllItemsSelected = useIsAllItemsSelected();
  const MAX_ENTITIES = 1000;

  const handleClose = () => {
    setOpen(false);
    setNewTag("");
  };

  const effectiveCount = isAllItemsSelected ? totalCount : rows.length;

  const handleAddTag = () => {
    if (!newTag) return;

    if (!isAllItemsSelected) {
      rows.forEach((item) => {
        const existingTags = item?.tags || [];
        bulkEditItems([item.id], { tags: [...existingTags, newTag] });
      });

      if (onSuccess) {
        onSuccess();
      }

      handleClose();
    } else {
      // Use API for filter-based tagging
      batchUpdateMutation.mutate(
        {
          datasetId,
          itemIds: rows.map((row) => row.id),
          item: { tags: [newTag] },
          mergeTags: true,
          isAllItemsSelected,
          filters,
          search,
          batchGroupId: isAllItemsSelected ? generateBatchGroupId() : undefined,
        },
        {
          onSuccess: () => {
            toast({
              title: "Success",
              description: `Tag "${newTag}" added to ${effectiveCount} dataset items`,
            });

            if (onSuccess) {
              onSuccess();
            }

            handleClose();
          },
        },
      );
    }
  };

  const isOverLimit = !isAllItemsSelected && rows.length > MAX_ENTITIES;

  return (
    <Dialog open={open} onOpenChange={handleClose}>
      <DialogContent className="sm:max-w-[425px]">
        <DialogHeader>
          <DialogTitle>Add tag to {effectiveCount} dataset items</DialogTitle>
        </DialogHeader>
        {isOverLimit && (
          <div className="mb-2 text-sm text-destructive">
            You can only add tags to up to {MAX_ENTITIES} items at a time.
            Please select fewer items.
          </div>
        )}
        <div className="grid gap-4 py-4">
          <div className="flex items-center gap-4">
            <Input
              placeholder="New tag"
              value={newTag}
              onChange={(event) => setNewTag(event.target.value)}
              onKeyDown={(event) => {
                if (event.key === "Enter" && newTag && !isOverLimit) {
                  handleAddTag();
                }
              }}
              className="col-span-3"
              disabled={isOverLimit}
            />
          </div>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={handleClose}>
            Cancel
          </Button>
          <Button onClick={handleAddTag} disabled={!newTag || isOverLimit}>
            Add tag
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default AddTagDialog;
