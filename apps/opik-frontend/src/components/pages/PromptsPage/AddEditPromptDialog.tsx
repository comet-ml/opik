import React, { useCallback, useState } from "react";
import CodeMirror from "@uiw/react-codemirror";
import { EditorView } from "@codemirror/view";
import { jsonLanguage } from "@codemirror/lang-json";
import { useNavigate } from "@tanstack/react-router";

import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogAutoScrollBody,
  DialogClose,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { Alert, AlertTitle } from "@/components/ui/alert";
import {
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
  Accordion,
} from "@/components/ui/accordion";
import { Prompt } from "@/types/prompts";
import usePromptCreateMutation from "@/api/prompts/usePromptCreateMutation";
import usePromptUpdateMutation from "@/api/prompts/usePromptUpdateMutation";
import { isValidJsonObject, safelyParseJSON } from "@/lib/utils";
import { useCodemirrorTheme } from "@/hooks/useCodemirrorTheme";
import { useBooleanTimeoutState } from "@/hooks/useBooleanTimeoutState";
import useAppStore from "@/store/AppStore";

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
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const navigate = useNavigate();
  const [name, setName] = useState(defaultPrompt?.name || "");
  const [template, setTemplate] = useState("");
  const [metadata, setMetadata] = useState("");
  const [description, setDescription] = useState(
    defaultPrompt?.description || "",
  );

  const [showInvalidJSON, setShowInvalidJSON] = useBooleanTimeoutState({});
  const theme = useCodemirrorTheme({
    editable: true,
  });

  const { mutate: createMutate } = usePromptCreateMutation();
  const { mutate: updateMutate } = usePromptUpdateMutation();

  const isEdit = !!defaultPrompt;
  const isValid = Boolean(name.length && (isEdit || template.length));
  const title = isEdit ? "Edit prompt" : "Create a new prompt";
  const submitText = isEdit ? "Update prompt" : "Create prompt";

  const onPromptCreated = useCallback(
    (prompt: Prompt) => {
      if (!prompt.id) return;

      navigate({
        to: "/$workspaceName/prompts/$promptId",
        params: {
          promptId: prompt.id,
          workspaceName,
        },
      });
    },
    [workspaceName, navigate],
  );

  const createPrompt = useCallback(() => {
    const isMetadataValid = metadata === "" || isValidJsonObject(metadata);

    if (!isMetadataValid) {
      return setShowInvalidJSON(true);
    }

    createMutate(
      {
        prompt: {
          name,
          template,
          ...(metadata && { metadata: safelyParseJSON(metadata) }),
          ...(description && { description }),
        },
      },
      { onSuccess: onPromptCreated },
    );
    setOpen(false);
  }, [
    metadata,
    createMutate,
    name,
    template,
    description,
    setOpen,
    setShowInvalidJSON,
    onPromptCreated,
  ]);

  const editPrompt = useCallback(() => {
    updateMutate({
      prompt: {
        id: defaultPrompt?.id,
        name,
        ...(description ? { description } : {}),
      },
    });
    setOpen(false);
  }, [updateMutate, defaultPrompt?.id, name, description, setOpen]);

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-lg sm:max-w-[720px]">
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
        </DialogHeader>
        <DialogAutoScrollBody>
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
            <Accordion
              type="multiple"
              defaultValue={
                defaultPrompt?.description ? ["description"] : undefined
              }
            >
              {!isEdit && (
                <AccordionItem value="metadata">
                  <AccordionTrigger>Metadata</AccordionTrigger>
                  <AccordionContent>
                    <div className="max-h-40 overflow-y-auto rounded-md">
                      <CodeMirror
                        theme={theme}
                        value={metadata}
                        onChange={setMetadata}
                        extensions={[jsonLanguage, EditorView.lineWrapping]}
                      />
                    </div>
                    <p className="comet-body-xs mt-2 text-light-slate">
                      {`Enter a valid JSON object using key-value pairs inside curly braces (e.g. {"key": "value"}).`}
                    </p>
                  </AccordionContent>
                </AccordionItem>
              )}
              {showInvalidJSON && (
                <Alert variant="destructive">
                  <AlertTitle>Metadata field is not valid</AlertTitle>
                </Alert>
              )}
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
        </DialogAutoScrollBody>
        <DialogFooter>
          <DialogClose asChild>
            <Button variant="outline">Cancel</Button>
          </DialogClose>
          <Button
            type="submit"
            disabled={!isValid}
            onClick={isEdit ? editPrompt : createPrompt}
          >
            {submitText}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default AddEditPromptDialog;
