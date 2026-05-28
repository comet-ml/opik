import React, { useCallback, useEffect, useState } from "react";

import { Button } from "@/ui/button";
import { Sheet, SheetContent, SheetTopBar } from "@/ui/sheet";
import { Input } from "@/ui/input";
import { Label } from "@/ui/label";
import { AutoGrowTextarea } from "@/ui/auto-grow-textarea";
import { Prompt } from "@/types/prompts";
import usePromptUpdateMutation from "@/api/prompts/usePromptUpdateMutation";

type EditPromptDetailsSheetProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
  prompt: Prompt;
};

/**
 * Edit the prompt's name + description only. The full prompt-template editor
 * (template, metadata, version notes) lives in the version-create flow; this
 * sheet just updates the prompt's identity metadata.
 */
const EditPromptDetailsSheet: React.FC<EditPromptDetailsSheetProps> = ({
  open,
  setOpen,
  prompt,
}) => {
  const [name, setName] = useState(prompt.name ?? "");
  const [description, setDescription] = useState(prompt.description ?? "");

  // Reset to the latest prompt props each time the sheet reopens so we don't
  // carry stale draft state if the user closes and picks a different row.
  useEffect(() => {
    if (!open) return;
    setName(prompt.name ?? "");
    setDescription(prompt.description ?? "");
  }, [open, prompt.name, prompt.description]);

  const { mutate, isPending: isSaving } = usePromptUpdateMutation();

  const isValid = name.trim().length > 0;
  const isDirty =
    name !== (prompt.name ?? "") || description !== (prompt.description ?? "");

  const handleSave = useCallback(() => {
    if (!isValid || isSaving) return;
    mutate(
      {
        prompt: {
          id: prompt.id,
          name,
          ...(description ? { description } : { description: "" }),
        },
      },
      {
        onSuccess: () => setOpen(false),
      },
    );
  }, [isValid, isSaving, mutate, prompt.id, name, description, setOpen]);

  useEffect(() => {
    if (!open) return;
    const handler = (e: KeyboardEvent) => {
      if (e.key === "Enter" && (e.metaKey || e.ctrlKey)) {
        e.preventDefault();
        handleSave();
      }
    };
    window.addEventListener("keydown", handler);
    return () => window.removeEventListener("keydown", handler);
  }, [open, handleSave]);

  return (
    <Sheet open={open} onOpenChange={setOpen}>
      <SheetContent
        side="right"
        className="flex w-full max-w-none flex-col p-0 sm:max-w-[520px]"
        header={<SheetTopBar variant="form" title="Edit prompt" />}
        blockOverlayClose={isDirty}
      >
        <div className="min-h-0 flex-1 space-y-4 overflow-y-auto px-6 pb-6">
          <div className="space-y-1.5">
            <Label htmlFor="editPromptName">Name</Label>
            <Input
              id="editPromptName"
              dimension="sm"
              placeholder="Prompt name"
              value={name}
              onChange={(e) => setName(e.target.value)}
              className="comet-body-s"
            />
          </div>

          <div className="space-y-1.5">
            <Label htmlFor="editPromptDescription">Description</Label>
            <AutoGrowTextarea
              id="editPromptDescription"
              dimension="sm"
              className="comet-body-s"
              value={description}
              onChange={setDescription}
              placeholder="Add optional description"
            />
          </div>
        </div>

        <div className="flex items-center justify-end gap-2 border-t px-6 py-3">
          <Button
            variant="outline"
            size="sm"
            onClick={() => setOpen(false)}
            disabled={isSaving}
          >
            Cancel
          </Button>
          <Button
            size="sm"
            type="submit"
            disabled={!isValid || !isDirty || isSaving}
            onClick={handleSave}
          >
            {isSaving ? "Saving…" : "Save changes"}
          </Button>
        </div>
      </SheetContent>
    </Sheet>
  );
};

export default EditPromptDetailsSheet;
