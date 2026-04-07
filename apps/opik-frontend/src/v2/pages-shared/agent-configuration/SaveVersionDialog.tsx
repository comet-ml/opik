import React from "react";

import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/ui/dialog";
import { Button } from "@/ui/button";
import { Input } from "@/ui/input";
import BlueprintDiffTable, {
  type BlueprintVersionInfo,
} from "./BlueprintDiffDialog/BlueprintDiffTable";

type SaveVersionDialogProps = {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  description: string;
  onDescriptionChange: (value: string) => void;
  onSave: () => Promise<void> | void;
  isSaving: boolean;
  base: BlueprintVersionInfo;
  diff: BlueprintVersionInfo;
};

const SaveVersionDialog: React.FC<SaveVersionDialogProps> = ({
  open,
  onOpenChange,
  description,
  onDescriptionChange,
  onSave,
  isSaving,
  base,
  diff,
}) => {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-lg sm:max-w-[1200px]">
        <DialogHeader>
          <DialogTitle>Save as new version</DialogTitle>
        </DialogHeader>
        <div className="flex flex-col gap-4">
          <div>
            <label className="comet-body-xs-accented mb-1.5 block text-foreground">
              Description
            </label>
            <Input
              placeholder="Describe what changed in this version…"
              value={description}
              onChange={(e) => onDescriptionChange(e.target.value)}
            />
          </div>
          <div>
            <h3 className="comet-title-xs mt-4">Diff</h3>
            {open && (
              <BlueprintDiffTable base={base} diff={diff} defaultOnlyDiff />
            )}
          </div>
        </div>
        <DialogFooter>
          <Button
            variant="outline"
            size="sm"
            onClick={() => onOpenChange(false)}
            disabled={isSaving}
          >
            Cancel
          </Button>
          <Button
            size="sm"
            onClick={async () => {
              await onSave();
              onOpenChange(false);
            }}
            disabled={isSaving}
          >
            {isSaving ? "Saving…" : "Save"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default SaveVersionDialog;
