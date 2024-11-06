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
import useCreatePromptVersionMutation from "@/api/prompts/useCreatePromptVersionMutation";

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
  const [promptTemplate, setPromptTemplate] = useState(parentPromptTemplate);

  const createPromptVersionMutation = useCreatePromptVersionMutation();

  const handleClickEditPrompt = () => {
    createPromptVersionMutation.mutate({
      name: promptName,
      template: promptTemplate,
      onSetActiveVersionId,
    });
  };

  const isValid =
    promptTemplate?.length && promptTemplate !== parentPromptTemplate;

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
            <Label htmlFor="promptTemplate">Prompt</Label>
            <Textarea
              className="comet-code h-[400px]"
              id="promptTemplate"
              value={promptTemplate}
              onChange={(e) => setPromptTemplate(e.target.value)}
            />
            <p className="comet-body-xs text-light-slate mt-1">
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
