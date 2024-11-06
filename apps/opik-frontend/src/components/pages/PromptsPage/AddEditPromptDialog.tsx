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
import { Textarea } from "@/components/ui/textarea";
import usePromptCreateMutation from "@/api/prompts/usePromptCreateMutation";
import { Prompt } from "@/types/prompts";
import {
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
  Accordion,
} from "@/components/ui/accordion";
import usePromptUpdateMutation from "@/api/prompts/usePromptUpdateMutation";

type AddPromptDialogProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
  prompt?: Prompt;
};

const AddEditPromptDialog: React.FunctionComponent<AddPromptDialogProps> = ({
  open,
  setOpen,
  prompt: defaultPrompt,
}) => {
  const [name, setName] = useState(defaultPrompt?.name || "");
  const [template, setTemplate] = useState("");
  const [description, setDescription] = useState(
    defaultPrompt?.description || "",
  );

  const promptCreateMutation = usePromptCreateMutation();
  const promptUpdateMutation = usePromptUpdateMutation();

  const isEdit = !!defaultPrompt;
  const isValid = Boolean(name.length && (isEdit || template.length));

  const createPrompt = useCallback(() => {
    promptCreateMutation.mutate({
      prompt: {
        name,
        template,
        ...(description ? { description } : {}),
      },
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [name, description, template, promptCreateMutation.mutate]);

  const editPrompt = useCallback(() => {
    promptUpdateMutation.mutate({
      prompt: {
        id: defaultPrompt?.id,
        name,
        ...(description ? { description } : {}),
      },
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [name, description, defaultPrompt?.id, promptUpdateMutation?.mutate]);

  const onActionClick = () => {
    if (isEdit) {
      return editPrompt();
    }

    createPrompt();
  };

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
        {!isEdit && (
          <div className="flex flex-col gap-2 pb-4">
            <Label htmlFor="template">Prompt</Label>
            <Textarea
              id="template"
              className="comet-code"
              placeholder="Prompt"
              value={template}
              onChange={(event) => setTemplate(event.target.value)}
            />
            <p className="comet-body-xs text-light-slate">
              You can specify variables using the &quot;mustache&quot; syntax:{" "}
              {"{{variable}}"}.
            </p>
          </div>
        )}
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
            <Button type="submit" disabled={!isValid} onClick={onActionClick}>
              {isEdit ? "Edit prompt" : "Create prompt"}
            </Button>
          </DialogClose>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default AddEditPromptDialog;
