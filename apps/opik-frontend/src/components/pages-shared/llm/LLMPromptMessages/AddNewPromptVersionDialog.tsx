import React, { useEffect, useMemo, useState } from "react";
import CodeMirror from "@uiw/react-codemirror";
import { jsonLanguage } from "@codemirror/lang-json";
import { EditorView } from "@codemirror/view";
import { SquareArrowOutUpRight } from "lucide-react";
import { Link } from "@tanstack/react-router";

import useAppStore from "@/store/AppStore";
import {
  Dialog,
  DialogAutoScrollBody,
  DialogClose,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Alert, AlertTitle } from "@/components/ui/alert";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import { Label } from "@/components/ui/label";
import { Button } from "@/components/ui/button";
import {
  Accordion,
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
} from "@/components/ui/accordion";
import useCreatePromptVersionMutation from "@/api/prompts/useCreatePromptVersionMutation";
import PromptsSelectBox from "@/components/pages-shared/llm/PromptsSelectBox/PromptsSelectBox";
import { useBooleanTimeoutState } from "@/hooks/useBooleanTimeoutState";
import { useCodemirrorTheme } from "@/hooks/useCodemirrorTheme";
import { isValidJsonObject, safelyParseJSON } from "@/lib/utils";
import { PromptWithLatestVersion } from "@/types/prompts";
import usePromptById from "@/api/prompts/usePromptById";
import usePromptCreateMutation from "@/api/prompts/usePromptCreateMutation";

const extractMetadata = (prompt?: PromptWithLatestVersion) => {
  return prompt?.latest_version?.metadata
    ? JSON.stringify(prompt.latest_version.metadata, null, 2)
    : "";
};

type AddNewPromptVersionDialogProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
  prompt?: PromptWithLatestVersion;
  template: string;
  onSave: (promptId: string) => void;
};

const AddNewPromptVersionDialog: React.FC<AddNewPromptVersionDialogProps> = ({
  open,
  setOpen,
  prompt,
  template,
  onSave,
}) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const [promptId, setPromptId] = useState<string | undefined>(prompt?.id);
  const isEdit = Boolean(promptId);

  const [metadata, setMetadata] = useState(extractMetadata(prompt));
  const [description, setDescription] = useState("");
  const [name, setName] = useState("");
  const [changeDescription, setChangeDescription] = useState("");

  const [showInvalidJSON, setShowInvalidJSON] = useBooleanTimeoutState({});
  const theme = useCodemirrorTheme({
    editable: true,
  });

  const { mutate: newVersionMutate } = useCreatePromptVersionMutation();
  const { mutate: createMutate } = usePromptCreateMutation();

  const { data: promptData, isPending } = usePromptById(
    { promptId: promptId! },
    { enabled: Boolean(promptId) && prompt?.id !== promptId },
  );

  useEffect(() => {
    setPromptId(prompt?.id);
  }, [prompt?.id]);

  const selectedPrompt = useMemo(() => {
    return !promptId
      ? undefined
      : promptId === prompt?.id
        ? prompt
        : promptData;
  }, [prompt, promptData, promptId]);

  useEffect(() => {
    if (!isPending) {
      setMetadata(extractMetadata(selectedPrompt));
    }
  }, [isPending, selectedPrompt]);

  const isValid = (isEdit ? !isPending : name.length) && template.length;

  const handleClickEditPrompt = () => {
    const isMetadataValid = metadata === "" || isValidJsonObject(metadata);

    if (!isMetadataValid) {
      return setShowInvalidJSON(true);
    }

    if (isEdit) {
      if (selectedPrompt) {
        newVersionMutate({
          name: selectedPrompt?.name,
          template,
          changeDescription,
          ...(metadata && { metadata: safelyParseJSON(metadata) }),
          onSuccess: (data) => onSave(data.prompt_id),
        });

        setOpen(false);
      }
    } else {
      createMutate(
        {
          prompt: {
            name,
            template,
            ...(metadata && { metadata: safelyParseJSON(metadata) }),
            ...(description && { description }),
          },
        },
        { onSuccess: (data) => onSave(data?.id) },
      );
      setOpen(false);
    }
  };

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-lg sm:max-w-[720px]">
        <DialogHeader>
          <DialogTitle>Save changes</DialogTitle>
        </DialogHeader>
        <DialogAutoScrollBody>
          <div className="flex flex-col gap-2 pb-4">
            <Label>Prompt</Label>
            <PromptsSelectBox
              onValueChange={setPromptId}
              value={promptId}
              clearable={false}
              refetchOnMount={true}
              asNewOption={true}
            />
          </div>
          {isEdit ? (
            <p className="comet-body-s whitespace-pre-wrap break-words pb-4 text-muted-slate">
              Saving your changes to {selectedPrompt?.name ?? ""} will
              automatically create a new commit. You can view previous versions
              anytime in the
              <Link
                onClick={(event) => event.stopPropagation()}
                to="/$workspaceName/prompts/$promptId"
                params={{ workspaceName, promptId: promptId! }}
                target="_blank"
              >
                <Button variant="link" size="sm">
                  Prompt library
                  <SquareArrowOutUpRight className="ml-1.5 mt-1 size-3.5 shrink-0" />
                </Button>
              </Link>
            </p>
          ) : (
            <p className="comet-body-s pb-4 text-muted-slate">
              A new prompt and first commit will be created in the
              <Link
                onClick={(event) => event.stopPropagation()}
                to="/$workspaceName/prompts"
                params={{ workspaceName }}
                target="_blank"
              >
                <Button variant="link" size="sm">
                  Prompt library
                  <SquareArrowOutUpRight className="ml-1.5 mt-1 size-3.5 shrink-0" />
                </Button>
              </Link>
            </p>
          )}
          {isEdit ? (
            <div className="flex flex-col gap-2 pb-4">
              <Label htmlFor="promptMetadata">Commit message</Label>
              <Textarea
                className="comet-code min-h-20"
                id="promptMetadata"
                value={changeDescription}
                onChange={(e) => setChangeDescription(e.target.value)}
              />
            </div>
          ) : (
            <div className="flex flex-col gap-2 pb-4">
              <Label htmlFor="promptName">Name</Label>
              <Input
                id="promptName"
                placeholder="Prompt name"
                value={name}
                onChange={(event) => setName(event.target.value)}
              />
            </div>
          )}

          <div className="flex flex-col gap-2 border-t border-border pb-4">
            <Accordion type="multiple">
              <AccordionItem value="metadata">
                <AccordionTrigger>Metadata</AccordionTrigger>
                <AccordionContent>
                  <div className="rounded-md">
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
              {showInvalidJSON && (
                <Alert variant="destructive">
                  <AlertTitle>Metadata field is not valid</AlertTitle>
                </Alert>
              )}
              {!isEdit && (
                <AccordionItem value="description">
                  <AccordionTrigger>Description</AccordionTrigger>
                  <AccordionContent>
                    <Textarea
                      placeholder="Prompt description"
                      value={description}
                      onChange={(event) => setDescription(event.target.value)}
                      maxLength={255}
                    />
                  </AccordionContent>
                </AccordionItem>
              )}
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
            onClick={handleClickEditPrompt}
          >
            Save changes
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default AddNewPromptVersionDialog;
