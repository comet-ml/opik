import React from "react";

import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { PromptVersion } from "@/types/prompts";
import useRestorePromptVersionMutation from "@/api/prompts/useRestorePromptVersionMutation";

type RestoreVersionDialogProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
  versionToRestore: PromptVersion | null;
  onSetActiveVersionId: (versionId: string) => void;
};

const RestoreVersionDialog: React.FunctionComponent<
  RestoreVersionDialogProps
> = ({ open, setOpen, versionToRestore, onSetActiveVersionId }) => {
  const restorePromptVersionMutation = useRestorePromptVersionMutation();
  const isLoading = restorePromptVersionMutation.isPending;

  const handleConfirm = () => {
    if (!versionToRestore) {
      return;
    }

    restorePromptVersionMutation.mutate(
      {
        promptId: versionToRestore.prompt_id,
        versionId: versionToRestore.id,
      },
      {
        onSuccess(data) {
          setOpen(false);
          onSetActiveVersionId(data.id);
        },
      },
    );
  };

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-lg sm:max-w-[560px]">
        <DialogHeader>
          <DialogTitle>Restore Version</DialogTitle>
          <DialogDescription>
            Are you sure you want to restore version{" "}
            <span className="font-medium">{versionToRestore?.commit}</span>?
            This will create a new version with the same content.
          </DialogDescription>
        </DialogHeader>

        <DialogFooter>
          <DialogClose asChild>
            <Button variant="outline">Cancel</Button>
          </DialogClose>
          <Button
            onClick={handleConfirm}
            disabled={isLoading || !versionToRestore}
          >
            {isLoading ? "Restoring..." : "Restore"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default RestoreVersionDialog;
