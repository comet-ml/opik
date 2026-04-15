import React, { useMemo, useState } from "react";

import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/ui/dialog";
import { Button } from "@/ui/button";
import { Input } from "@/ui/input";
import { Label } from "@/ui/label";
import { Textarea } from "@/ui/textarea";
import { BLUEPRINT_FIELD_NAME_PATTERN } from "@/v2/pages-shared/agent-configuration/blueprintFieldValidation";

interface SaveAsNewBlueprintFieldDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  existingFieldNames: string[];
  isSaving: boolean;
  onSave: (fieldName: string, changeDescription: string) => void;
}

const validate = (value: string, existing: Set<string>): string | null => {
  if (!value) return "Field name is required";
  if (!BLUEPRINT_FIELD_NAME_PATTERN.test(value))
    return "Use letters, digits and underscore; start with a letter or underscore";
  if (existing.has(value)) return "A field with this name already exists";
  return null;
};

const SaveAsNewBlueprintFieldDialog: React.FC<
  SaveAsNewBlueprintFieldDialogProps
> = ({ open, onOpenChange, existingFieldNames, isSaving, onSave }) => {
  const [fieldName, setFieldName] = useState("");
  const [changeDescription, setChangeDescription] = useState("");
  const existing = useMemo(
    () => new Set(existingFieldNames),
    [existingFieldNames],
  );

  const trimmed = fieldName.trim();
  const error = trimmed ? validate(trimmed, existing) : null;
  const canSave = !!trimmed && !error;

  const handleClose = (next: boolean) => {
    if (!next) {
      setFieldName("");
      setChangeDescription("");
    }
    onOpenChange(next);
  };

  return (
    <Dialog open={open} onOpenChange={handleClose}>
      <DialogContent className="max-w-lg">
        <DialogHeader>
          <DialogTitle>Add to agent configuration</DialogTitle>
        </DialogHeader>
        <p className="comet-body-s pb-4 text-muted-slate">
          Creates a new prompt and adds it to the latest agent configuration as
          a new field. The same name is used for both the prompt and the
          configuration field.
        </p>
        <div className="flex flex-col gap-2 pb-4">
          <Label htmlFor="fieldName">Field name</Label>
          <Input
            id="fieldName"
            value={fieldName}
            onChange={(e) => setFieldName(e.target.value)}
            placeholder="e.g. summarization_prompt"
            autoFocus
          />
          {error && <p className="comet-body-xs text-destructive">{error}</p>}
        </div>
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
          <Button
            onClick={() => onSave(trimmed, changeDescription.trim())}
            disabled={!canSave || isSaving}
          >
            Save to configuration
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default SaveAsNewBlueprintFieldDialog;
