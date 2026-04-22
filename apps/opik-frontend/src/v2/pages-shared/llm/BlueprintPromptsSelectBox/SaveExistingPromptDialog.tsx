import React, { useState } from "react";

import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/ui/dialog";
import { Button } from "@/ui/button";
import { Label } from "@/ui/label";
import { Textarea } from "@/ui/textarea";

interface SaveExistingPromptDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  promptName: string;
  fieldName: string;
  isSaving: boolean;
  onSave: (changeDescription: string) => void;
}

const SaveExistingPromptDialog: React.FC<SaveExistingPromptDialogProps> = ({
  open,
  onOpenChange,
  promptName,
  fieldName,
  isSaving,
  onSave,
}) => {
  const [changeDescription, setChangeDescription] = useState("");

  const handleSave = () => onSave(changeDescription.trim());

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        if (!next) setChangeDescription("");
        onOpenChange(next);
      }}
    >
      <DialogContent className="max-w-lg">
        <DialogHeader>
          <DialogTitle>Update {promptName}</DialogTitle>
        </DialogHeader>
        <p className="comet-body-s pb-4 text-muted-slate">
          Creates a new agent configuration version with the updated prompt for
          field <span className="comet-body-s-accented">{fieldName}</span>.
        </p>
        <div className="flex flex-col gap-2 pb-4">
          <Label htmlFor="changeDescription">
            Change description (optional)
          </Label>
          <Textarea
            id="changeDescription"
            value={changeDescription}
            onChange={(e) => setChangeDescription(e.target.value)}
            placeholder="What changed in this version?"
            className="min-h-20"
          />
        </div>
        <DialogFooter>
          <DialogClose asChild>
            <Button variant="outline" disabled={isSaving}>
              Cancel
            </Button>
          </DialogClose>
          <Button onClick={handleSave} disabled={isSaving}>
            Save new version
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default SaveExistingPromptDialog;
