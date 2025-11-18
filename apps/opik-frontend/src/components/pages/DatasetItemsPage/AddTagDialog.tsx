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
import useDatasetItemUpdateMutation from "@/api/datasets/useDatasetItemUpdateMutation";

type AddTagDialogProps = {
  datasetId: string;
  rows: Array<DatasetItem>;
  open: boolean;
  setOpen: (open: boolean) => void;
  onSuccess?: () => void;
};

const AddTagDialog: React.FunctionComponent<AddTagDialogProps> = ({
  datasetId,
  rows,
  open,
  setOpen,
  onSuccess,
}) => {
  const { toast } = useToast();
  const [newTag, setNewTag] = useState<string>("");
  const updateMutation = useDatasetItemUpdateMutation();
  const MAX_ENTITIES = 10;

  const handleClose = () => {
    setOpen(false);
    setNewTag("");
  };

  const handleAddTag = () => {
    if (!newTag) return;

    const promises: Promise<unknown>[] = [];

    rows.forEach((row) => {
      const currentTags = row.tags || [];

      if (currentTags.includes(newTag)) return;

      const newTags = [...currentTags, newTag];

      promises.push(
        updateMutation.mutateAsync({
          datasetId,
          itemId: row.id,
          item: { tags: newTags },
        }),
      );
    });

    Promise.all(promises)
      .then(() => {
        toast({
          title: "Success",
          description: `Tag "${newTag}" added to ${rows.length} selected dataset items`,
        });

        if (onSuccess) {
          onSuccess();
        }

        handleClose();
      })
      .catch(() => {
        // Error handling is already done by the mutation hooks, this just ensures we don't close the dialog on error
      });
  };

  return (
    <Dialog open={open} onOpenChange={handleClose}>
      <DialogContent className="sm:max-w-[425px]">
        <DialogHeader>
          <DialogTitle>Add tag to {rows.length} dataset items</DialogTitle>
        </DialogHeader>
        {rows.length > MAX_ENTITIES && (
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
              className="col-span-3"
              disabled={rows.length > MAX_ENTITIES}
            />
          </div>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={handleClose}>
            Cancel
          </Button>
          <Button
            onClick={handleAddTag}
            disabled={!newTag || rows.length > MAX_ENTITIES}
          >
            Add tag
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default AddTagDialog;
