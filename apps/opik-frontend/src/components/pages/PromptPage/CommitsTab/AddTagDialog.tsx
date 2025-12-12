import React, { useState } from "react";
import { PromptVersion } from "@/types/prompts";
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
import usePromptVersionsUpdateMutation from "@/api/prompts/usePromptVersionsUpdateMutation";

type AddTagDialogProps = {
  rows: Array<PromptVersion>;
  open: boolean | number;
  setOpen: (open: boolean | number) => void;
  onSuccess?: () => void;
};

const AddTagDialog: React.FunctionComponent<AddTagDialogProps> = ({
  rows,
  open,
  setOpen,
  onSuccess,
}) => {
  const { toast } = useToast();
  const [newTag, setNewTag] = useState<string>("");
  const updateMutation = usePromptVersionsUpdateMutation();

  const handleClose = () => {
    setOpen(false);
    setNewTag("");
  };

  const handleAddTag = () => {
    if (!newTag) return;

    const versionIds = rows.map((row) => row.id);

    updateMutation.mutate(
      {
        versionIds,
        tags: [newTag],
        mergeTags: true,
      },
      {
        onSuccess: () => {
          toast({
            title: "Success",
            description: `Version tag "${newTag}" added to ${
              rows.length
            } selected prompt version${rows.length > 1 ? "s" : ""}`,
          });

          if (onSuccess) {
            onSuccess();
          }

          handleClose();
        },
      },
    );
  };

  return (
    <Dialog open={Boolean(open)} onOpenChange={handleClose}>
      <DialogContent className="sm:max-w-[425px]">
        <DialogHeader>
          <DialogTitle>
            Add version tag to {rows.length} prompt version
            {rows.length > 1 ? "s" : ""}
          </DialogTitle>
        </DialogHeader>
        <div className="grid gap-4 py-4">
          <div className="flex items-center gap-4">
            <Input
              placeholder="New version tag"
              value={newTag}
              onChange={(event) => setNewTag(event.target.value)}
              className="col-span-3"
            />
          </div>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={handleClose}>
            Cancel
          </Button>
          <Button onClick={handleAddTag} disabled={!newTag}>
            Add version tag
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default AddTagDialog;
