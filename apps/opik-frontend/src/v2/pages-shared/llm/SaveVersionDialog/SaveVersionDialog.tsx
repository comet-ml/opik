import React, { useEffect, useState } from "react";

import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/ui/dialog";
import { Button } from "@/ui/button";
import { AutoGrowTextarea } from "@/ui/auto-grow-textarea";
import { Label } from "@/ui/label";

type SaveVersionDialogProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
  promptName: string;
  isSaving?: boolean;
  onSave: (changeDescription: string) => void;
};

const SaveVersionDialog: React.FC<SaveVersionDialogProps> = ({
  open,
  setOpen,
  promptName,
  isSaving = false,
  onSave,
}) => {
  const [changeDescription, setChangeDescription] = useState("");

  useEffect(() => {
    if (open) setChangeDescription("");
  }, [open]);

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-lg sm:max-w-[480px]">
        <DialogHeader>
          <DialogTitle>Save new version of {promptName}</DialogTitle>
        </DialogHeader>
        <div className="space-y-1.5 pb-4">
          <Label htmlFor="versionNotes">Version notes</Label>
          <AutoGrowTextarea
            id="versionNotes"
            dimension="sm"
            className="comet-body-s"
            value={changeDescription}
            onChange={setChangeDescription}
            placeholder="Describe what changed in this version"
          />
        </div>
        <DialogFooter>
          <DialogClose asChild>
            <Button variant="outline">Cancel</Button>
          </DialogClose>
          <Button
            type="submit"
            disabled={isSaving}
            onClick={() => onSave(changeDescription)}
          >
            Save version
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default SaveVersionDialog;
