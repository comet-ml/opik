import React, { useState } from "react";

import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Textarea } from "@/components/ui/textarea";
import { Label } from "@/components/ui/label";
import { Button } from "@/components/ui/button";
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group";
import useCreatePromptVersionMutation from "@/api/prompts/useCreatePromptVersionMutation";
import TextDiff from "@/components/shared/CodeDiff/TextDiff";

enum PROMPT_PREVIEW_MODE {
  write = "write",
  diff = "diff",
}

type EditPromptDialogProps = {
  open: boolean;
  setOpen: (open: boolean) => void;

  promptTemplate: string;
  promptName: string;
  onSetActiveVersionId: (versionId: string) => void;
};

const EditPromptDialog: React.FunctionComponent<EditPromptDialogProps> = ({
  open,
  setOpen,
  promptTemplate: parentPromptTemplate,
  promptName,
  onSetActiveVersionId,
}) => {
  const [previewMode, setPreviewMode] = useState<PROMPT_PREVIEW_MODE>(
    PROMPT_PREVIEW_MODE.write,
  );
  const [promptTemplate, setPromptTemplate] = useState(parentPromptTemplate);

  const createPromptVersionMutation = useCreatePromptVersionMutation();

  const handleClickEditPrompt = () => {
    createPromptVersionMutation.mutate({
      name: promptName,
      template: promptTemplate,
      onSetActiveVersionId,
    });
  };

  const templateHasChanges = promptTemplate !== parentPromptTemplate;
  const isValid = promptTemplate?.length && templateHasChanges;

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-lg sm:max-w-[720px]">
        <DialogHeader>
          <DialogTitle>Edit prompt</DialogTitle>
        </DialogHeader>
        <div className="size-full overflow-y-auto">
          <p className="comet-body-s text-muted-slate ">
            By editing a prompt, a new commit will be created automatically. You
            can access older versions of the prompt from the <b>Commits</b> tab.
          </p>

          <div className="py-4">
            <div className="mb-3 flex items-center justify-between">
              <Label htmlFor="promptTemplate">Prompt</Label>
              <ToggleGroup
                type="single"
                value={previewMode}
                onValueChange={setPreviewMode as never}
                size="sm"
              >
                <ToggleGroupItem
                  value={PROMPT_PREVIEW_MODE.write}
                  aria-label="Write"
                >
                  Write
                </ToggleGroupItem>
                <ToggleGroupItem
                  value={PROMPT_PREVIEW_MODE.diff}
                  aria-label="Preview changes"
                  disabled={!templateHasChanges}
                >
                  Preview changes
                </ToggleGroupItem>
              </ToggleGroup>
            </div>
            {previewMode === PROMPT_PREVIEW_MODE.write ? (
              <Textarea
                className="comet-code h-[400px] resize-none"
                id="promptTemplate"
                value={promptTemplate}
                onChange={(e) => setPromptTemplate(e.target.value)}
              />
            ) : (
              <div className="comet-code h-[400px] whitespace-pre-line break-words rounded-md border px-3 py-2">
                <TextDiff
                  content1={parentPromptTemplate}
                  content2={promptTemplate}
                />
              </div>
            )}

            <p className="comet-body-xs mt-3 text-light-slate">
              You can specify variables using the &quot;mustache&quot; syntax:{" "}
              {"{{variable}}"}.
            </p>
          </div>
        </div>

        <DialogFooter>
          <DialogClose asChild>
            <Button variant="outline">Cancel</Button>
          </DialogClose>
          <DialogClose asChild>
            <Button
              type="submit"
              disabled={!isValid}
              onClick={handleClickEditPrompt}
            >
              Edit prompt
            </Button>
          </DialogClose>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default EditPromptDialog;
