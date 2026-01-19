import React, { useState } from "react";
import { Experiment } from "@/types/datasets";
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
import useExperimentBatchUpdateMutation from "@/api/datasets/useExperimentBatchUpdateMutation";

type AddTagDialogProps = {
  experiments: Experiment[];
  open: boolean | number;
  setOpen: (open: boolean | number) => void;
  onSuccess?: () => void;
};

const AddTagDialog: React.FunctionComponent<AddTagDialogProps> = ({
  experiments,
  open,
  setOpen,
  onSuccess,
}) => {
  const { toast } = useToast();
  const [newTag, setNewTag] = useState<string>("");
  const experimentBatchUpdateMutation = useExperimentBatchUpdateMutation();

  const handleClose = () => {
    setOpen(false);
    setNewTag("");
  };

  const handleAddTag = () => {
    const tag = newTag.trim();

    if (!tag) {
      toast({
        variant: "destructive",
        title: "Invalid tag",
        description: "Tag cannot be empty or contain only whitespace",
      });
      return;
    }

    if (tag.length > 100) {
      toast({
        variant: "destructive",
        title: "Invalid tag",
        description: "Tag cannot exceed 100 characters",
      });
      return;
    }

    const ids = experiments.map((exp) => exp.id);

    experimentBatchUpdateMutation
      .mutateAsync({
        ids,
        experiment: {
          tags: [tag],
        },
        mergeTags: true,
      })
      .then(() => {
        toast({
          title: "Success",
          description: `Tag "${tag}" added to ${
            experiments.length
          } selected experiment${experiments.length > 1 ? "s" : ""}`,
        });

        if (onSuccess) {
          onSuccess();
        }

        handleClose();
      })
      .catch(() => {
        // Error handling is already done by the mutation hook
      });
  };

  return (
    <Dialog open={Boolean(open)} onOpenChange={handleClose}>
      <DialogContent className="sm:max-w-[425px]">
        <DialogHeader>
          <DialogTitle>
            Add tag to {experiments.length} experiment
            {experiments.length > 1 ? "s" : ""}
          </DialogTitle>
        </DialogHeader>
        <div className="grid gap-4 py-4">
          <div className="flex items-center gap-4">
            <Input
              placeholder="New tag"
              value={newTag}
              onChange={(event) => setNewTag(event.target.value)}
              maxLength={100}
              className="col-span-3"
            />
          </div>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={handleClose}>
            Cancel
          </Button>
          <Button
            onClick={handleAddTag}
            disabled={!newTag.trim() || newTag.length > 100}
          >
            Add tag
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default AddTagDialog;
