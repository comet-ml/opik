import React, { useCallback, useState } from "react";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import useAppStore from "@/store/AppStore";
import { Textarea } from "@/components/ui/textarea";
import usePromptCreateMutation from "@/api/prompts/usePromptCreateMutation";
import { Prompt } from "@/types/prompts";
import {
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
  Accordion,
} from "@/components/ui/accordion";

type AddPromptDialogProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
  onPromptCreated?: (prompt: Prompt) => void;
};

const AddPromptDialog: React.FunctionComponent<AddPromptDialogProps> = ({
  open,
  setOpen,
  onPromptCreated,
}) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const promptCreateMutation = usePromptCreateMutation();
  const [name, setName] = useState("");
  const [prompt, setPrompt] = useState("");
  const [description, setDescription] = useState("");

  const isValid = Boolean(name.length && prompt.length);

  const createPrompt = useCallback(() => {
    promptCreateMutation.mutate(
      {
        prompt: {
          name,
          template: prompt,
          ...(description ? { description } : {}),
        },
        workspaceName,
      },
      { onSuccess: onPromptCreated },
    );
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [name, description, workspaceName, onPromptCreated]);

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-lg sm:max-w-[720px]">
        <DialogHeader>
          <DialogTitle>Create a new prompt</DialogTitle>
        </DialogHeader>
        <div className="flex flex-col gap-2 pb-4">
          <Label htmlFor="promptName">Name</Label>
          <Input
            id="promptName"
            placeholder="Prompt name"
            value={name}
            onChange={(event) => setName(event.target.value)}
          />
        </div>
        <div className="flex flex-col gap-2 pb-4">
          <Label htmlFor="prompt">Prompt</Label>
          <Textarea
            id="prompt"
            className="comet-code"
            placeholder="Prompt"
            value={prompt}
            onChange={(event) => setPrompt(event.target.value)}
          />
          <p className="comet-body-xs text-light-slate">
            You can specify variables using the &quot;mustache&quot; syntax:{" "}
            {"{{variable}}"}.
          </p>
        </div>
        <div className="flex flex-col gap-2 border-t border-border pb-4">
          <Accordion type="multiple">
            <AccordionItem value="description">
              <AccordionTrigger>Description</AccordionTrigger>
              <AccordionContent>
                <Textarea
                  id="promptDescription"
                  placeholder="Prompt description"
                  value={description}
                  onChange={(event) => setDescription(event.target.value)}
                  maxLength={255}
                />
              </AccordionContent>
            </AccordionItem>
          </Accordion>
        </div>
        <DialogFooter>
          <DialogClose asChild>
            <Button variant="outline">Cancel</Button>
          </DialogClose>
          <DialogClose asChild>
            <Button type="submit" disabled={!isValid} onClick={createPrompt}>
              Create prompt
            </Button>
          </DialogClose>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default AddPromptDialog;
