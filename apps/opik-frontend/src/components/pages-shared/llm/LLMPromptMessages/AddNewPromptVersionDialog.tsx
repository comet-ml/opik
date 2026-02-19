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
import { Description } from "@/components/ui/description";
import { Button } from "@/components/ui/button";
import {
  Accordion,
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
} from "@/components/ui/accordion";
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group";
import useCreatePromptVersionMutation from "@/api/prompts/useCreatePromptVersionMutation";
import PromptsSelectBox from "@/components/pages-shared/llm/PromptsSelectBox/PromptsSelectBox";

import { useBooleanTimeoutState } from "@/hooks/useBooleanTimeoutState";
import { useCodemirrorTheme } from "@/hooks/useCodemirrorTheme";
import { isValidJsonObject, safelyParseJSON } from "@/lib/utils";
import {
  PromptVersion,
  PromptWithLatestVersion,
  PROMPT_TEMPLATE_STRUCTURE,
} from "@/types/prompts";
import usePromptById from "@/api/prompts/usePromptById";
import usePromptCreateMutation from "@/api/prompts/usePromptCreateMutation";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";

type SaveMode = "update" | "new";

const getInitialMetadata = (
  prompt?: PromptWithLatestVersion,
  fallbackMetadata?: object,
): string => {
  if (prompt?.latest_version?.metadata) {
    return JSON.stringify(prompt.latest_version.metadata, null, 2);
  }
  if (fallbackMetadata) {
    return JSON.stringify(fallbackMetadata, null, 2);
  }
  return "";
};

type AddNewPromptVersionDialogProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
  prompt?: PromptWithLatestVersion;
  template: string;
  templateStructure?: PROMPT_TEMPLATE_STRUCTURE;
  defaultName?: string;
  metadata?: object;
  onSave: (
    version: PromptVersion,
    promptName?: string,
    promptId?: string,
  ) => void;
};

const AddNewPromptVersionDialog: React.FC<AddNewPromptVersionDialogProps> = ({
  open,
  setOpen,
  prompt,
  template,
  templateStructure = PROMPT_TEMPLATE_STRUCTURE.TEXT,
  defaultName = "",
  metadata: providedMetadata,
  onSave,
}) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const [promptId, setPromptId] = useState<string | undefined>(prompt?.id);
  const [saveMode, setSaveMode] = useState<SaveMode>(
    prompt?.id ? "update" : "new",
  );
  const isEdit = saveMode === "update" && Boolean(promptId);

  const [metadata, setMetadata] = useState(
    getInitialMetadata(prompt, providedMetadata),
  );
  const [description, setDescription] = useState("");
  const [name, setName] = useState(defaultName);
  const [changeDescription, setChangeDescription] = useState("");

  const [showInvalidJSON, setShowInvalidJSON] = useBooleanTimeoutState({});
  const theme = useCodemirrorTheme({
    editable: true,
  });

  const { mutate: newVersionMutate } = useCreatePromptVersionMutation();
  const { mutate: createMutate } = usePromptCreateMutation();

  const needsFetch = Boolean(promptId) && prompt?.id !== promptId;
  const { data: promptData, isPending: isFetchPending } = usePromptById(
    { promptId: promptId! },
    { enabled: needsFetch },
  );

  const isPending = needsFetch && isFetchPending;

  useEffect(() => {
    setPromptId(prompt?.id);
    setSaveMode(prompt?.id ? "update" : "new");
  }, [prompt?.id]);

  useEffect(() => {
    // Reset name when dialog opens or save mode changes:
    // - If "Save as new" mode is selected, clear name for user to enter new one
    // - Otherwise use defaultName
    if (open) {
      const shouldClearName = saveMode === "new" && prompt;
      setName(shouldClearName ? "" : defaultName);
    }
  }, [open, defaultName, saveMode, prompt]);

  const selectedPrompt = useMemo(() => {
    return !promptId
      ? undefined
      : promptId === prompt?.id
        ? prompt
        : promptData;
  }, [prompt, promptData, promptId]);

  useEffect(() => {
    if (!isPending && metadata === "") {
      setMetadata(getInitialMetadata(selectedPrompt, providedMetadata));
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isPending, selectedPrompt, providedMetadata]);

  const hasValidTemplate = template.length > 0;
  const canSaveNewPrompt = !isEdit && name.length > 0;
  const canSaveExistingPrompt = isEdit && !isPending && Boolean(selectedPrompt);

  const isValid =
    hasValidTemplate && (canSaveNewPrompt || canSaveExistingPrompt);

  const handleClickEditPrompt = () => {
    const isMetadataValid = metadata === "" || isValidJsonObject(metadata);

    if (!isMetadataValid) {
      return setShowInvalidJSON(true);
    }

    // Merge user-entered metadata with provided metadata (from playground)
    // Provided metadata takes precedence for specific fields
    const userMetadata = metadata ? safelyParseJSON(metadata) : {};
    const finalMetadata =
      providedMetadata || Object.keys(userMetadata).length > 0
        ? { ...userMetadata, ...providedMetadata }
        : undefined;

    if (isEdit) {
      if (selectedPrompt) {
        const promptType = selectedPrompt?.latest_version?.type;

        newVersionMutate({
          name: selectedPrompt?.name,
          template,
          changeDescription,
          ...(finalMetadata && { metadata: finalMetadata }),
          ...(templateStructure && { templateStructure }),
          ...(promptType && { type: promptType }),
          onSuccess: (data) =>
            onSave(data, selectedPrompt?.name, selectedPrompt?.id),
        });

        setOpen(false);
      }
    } else {
      createMutate(
        {
          prompt: {
            name,
            template,
            template_structure: templateStructure,
            ...(finalMetadata && { metadata: finalMetadata }),
            ...(description && { description }),
          },
          withResponse: true,
        },
        {
          onSuccess: (data?: PromptWithLatestVersion) => {
            if (data?.latest_version)
              onSave(data.latest_version, data.name, data.id);
          },
        },
      );
      setOpen(false);
    }
  };

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-lg sm:max-w-[620px]">
        <DialogHeader>
          <DialogTitle>Save to prompt library</DialogTitle>
        </DialogHeader>
        <DialogAutoScrollBody>
          {prompt && (
            <div className="flex flex-col gap-2 pb-4">
              <ToggleGroup
                type="single"
                value={saveMode}
                onValueChange={(val) => val && setSaveMode(val as SaveMode)}
                variant="ghost"
                className="w-fit"
              >
                <ToggleGroupItem value="update" size="sm">
                  Update existing
                </ToggleGroupItem>
                <ToggleGroupItem value="new" size="sm">
                  Save as new
                </ToggleGroupItem>
              </ToggleGroup>
            </div>
          )}

          {saveMode === "update" && prompt && (
            <div className="flex flex-col gap-2 pb-4">
              <Label>Prompt</Label>
              <PromptsSelectBox
                onValueChange={setPromptId}
                value={promptId}
                clearable={false}
                refetchOnMount={true}
                filterByTemplateStructure={templateStructure}
              />
              <Description>
                Selected prompt will be updated. You can view versions in the
                <Link
                  onClick={(event) => event.stopPropagation()}
                  to="/$workspaceName/prompts/$promptId"
                  params={{ workspaceName, promptId: promptId! }}
                  target="_blank"
                >
                  <Button variant="link" size="sm" className="px-1">
                    Prompt library
                    <SquareArrowOutUpRight className="ml-1.5 mt-1 size-3.5 shrink-0" />
                  </Button>
                </Link>
              </Description>
            </div>
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
            <>
              <div className="flex flex-col gap-2">
                <Label htmlFor="promptName">Name</Label>
                <Input
                  id="promptName"
                  placeholder="Prompt name"
                  value={name}
                  onChange={(event) => setName(event.target.value)}
                />
              </div>

              <div className="flex flex-col gap-2 pb-4">
                <Description>
                  A new prompt will be created in the
                  <Link
                    onClick={(event) => event.stopPropagation()}
                    to="/$workspaceName/prompts"
                    params={{ workspaceName }}
                    target="_blank"
                  >
                    <Button variant="link" size="sm" className="px-1">
                      Prompt library
                      <SquareArrowOutUpRight className="ml-1.5 mt-1 size-3.5 shrink-0" />
                    </Button>
                  </Link>
                </Description>
              </div>

              <div className="flex flex-col gap-2 pb-4">
                <Label htmlFor="promptDescription">
                  Description (optional)
                </Label>
                <Textarea
                  id="promptDescription"
                  placeholder="Prompt description"
                  value={description}
                  onChange={(event) => setDescription(event.target.value)}
                  maxLength={255}
                  className="min-h-20"
                />
              </div>
            </>
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
                  <Description className="mt-2 block">
                    {
                      EXPLAINERS_MAP[
                        EXPLAINER_ID.what_format_should_the_metadata_be
                      ].description
                    }
                  </Description>
                </AccordionContent>
              </AccordionItem>
              {showInvalidJSON && (
                <Alert variant="destructive">
                  <AlertTitle>Metadata field is not valid</AlertTitle>
                </Alert>
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
            Save to library
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default AddNewPromptVersionDialog;
