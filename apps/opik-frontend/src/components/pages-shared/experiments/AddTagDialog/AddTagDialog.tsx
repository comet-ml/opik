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
    if (!newTag) return;

    const ids = experiments.map((exp) => exp.id);

    experimentBatchUpdateMutation
      .mutateAsync({
        ids,
        experiment: {
          tags: [newTag],
        },
        mergeTags: true,
      })
      .then(() => {
        toast({
          title: "Success",
          description: `Tag "${newTag}" added to ${experiments.length} selected experiment${experiments.length > 1 ? "s" : ""}`,
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
            Add tag to {experiments.length} experiment{experiments.length > 1 ? "s" : ""}
          </DialogTitle>
        </DialogHeader>
        <div className="grid gap-4 py-4">
          <div className="flex items-center gap-4">
            <Input
              placeholder="New tag"
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
            Add tag
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default AddTagDialog;