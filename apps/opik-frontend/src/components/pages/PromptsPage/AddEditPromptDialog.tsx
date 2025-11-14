import React, { useCallback, useState } from "react";
import CodeMirror from "@uiw/react-codemirror";
import { EditorView } from "@codemirror/view";
import { jsonLanguage } from "@codemirror/lang-json";
import { useNavigate } from "@tanstack/react-router";
import { Code2, MessageSquare } from "lucide-react";

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
import { Description } from "@/components/ui/description";
import { Textarea } from "@/components/ui/textarea";
import { Alert, AlertTitle } from "@/components/ui/alert";
import {
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
  Accordion,
} from "@/components/ui/accordion";
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group";
import { Prompt } from "@/types/prompts";
import { LLMMessage } from "@/types/llm";
import LLMPromptMessages from "@/components/pages-shared/llm/LLMPromptMessages/LLMPromptMessages";
import { generateDefaultLLMPromptMessage } from "@/lib/llm";
import usePromptCreateMutation from "@/api/prompts/usePromptCreateMutation";
import usePromptUpdateMutation from "@/api/prompts/usePromptUpdateMutation";
import { isValidJsonObject, safelyParseJSON } from "@/lib/utils";
import { useCodemirrorTheme } from "@/hooks/useCodemirrorTheme";
import { useBooleanTimeoutState } from "@/hooks/useBooleanTimeoutState";
import useAppStore from "@/store/AppStore";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";
import ExplainerDescription from "@/components/shared/ExplainerDescription/ExplainerDescription";
import PromptMessageImageTags from "@/components/pages-shared/llm/PromptMessageImageTags/PromptMessageImageTags";
import { useMessageContent } from "@/hooks/useMessageContent";

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
  const [templateStructure, setTemplateStructure] = useState<"text" | "chat">(
    "text",
  );
  const [messages, setMessages] = useState<LLMMessage[]>([
    generateDefaultLLMPromptMessage(),
  ]);
  const [showRawView, setShowRawView] = useState(false);

  const [showInvalidJSON, setShowInvalidJSON] = useBooleanTimeoutState({});
  const theme = useCodemirrorTheme({
    editable: true,
  });

  const { localText, images, setImages, handleContentChange } =
    useMessageContent({
      content: template,
      onChangeContent: setTemplate,
    });

  const { mutate: createMutate } = usePromptCreateMutation();
  const { mutate: updateMutate } = usePromptUpdateMutation();

  const isEdit = !!defaultPrompt;
  const isChatPrompt = templateStructure === "chat";
  const isValid = Boolean(
    name.length &&
      (isEdit || (isChatPrompt ? messages.length > 0 : template.length)),
  );
  const title = isEdit ? "Edit prompt" : "Create a new prompt";
  const submitText = isEdit ? "Update prompt" : "Create prompt";

  const handleAddMessage = useCallback(() => {
    setMessages((prev) => [...prev, generateDefaultLLMPromptMessage()]);
  }, []);

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

    // For chat prompts, convert messages to JSON string
    const promptTemplate = isChatPrompt
      ? JSON.stringify(
          messages.map((m) => ({
            role: m.role,
            content: m.content,
          })),
        )
      : template;

    createMutate(
      {
        prompt: {
          name,
          template: promptTemplate,
          template_structure: templateStructure,
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
    templateStructure,
    isChatPrompt,
    messages,
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
          {!isEdit && (
            <ExplainerDescription
              className="mb-4"
              {...EXPLAINERS_MAP[EXPLAINER_ID.how_do_i_write_my_prompt]}
            />
          )}
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
              <Label htmlFor="templateStructure">Prompt type</Label>
              <RadioGroup
                value={templateStructure}
                onValueChange={(value) =>
                  setTemplateStructure(value as "text" | "chat")
                }
              >
                <div className="flex items-center space-x-2">
                  <RadioGroupItem value="text" id="text" />
                  <Label
                    htmlFor="text"
                    className="cursor-pointer font-normal"
                  >
                    Text prompt - Single text template
                  </Label>
                </div>
                <div className="flex items-center space-x-2">
                  <RadioGroupItem value="chat" id="chat" />
                  <Label htmlFor="chat" className="cursor-pointer font-normal">
                    Chat prompt - Array of messages with roles
                  </Label>
                </div>
              </RadioGroup>
              <Description>
                Choose text for single text prompts or chat for
                conversation-style prompts with multiple messages.
              </Description>
            </div>
          )}
          {!isEdit && !isChatPrompt && (
            <div className="flex flex-col gap-2 pb-4">
              <Label htmlFor="template">Prompt</Label>
              <Textarea
                id="template"
                className="comet-code"
                placeholder="Prompt"
                value={localText}
                onChange={(event) => handleContentChange(event.target.value)}
              />
              <Description>
                {
                  EXPLAINERS_MAP[EXPLAINER_ID.what_format_should_the_prompt_be]
                    .description
                }
              </Description>
            </div>
          )}
          {!isEdit && !isChatPrompt && (
            <div className="flex flex-col gap-2 pb-4">
              <Label>Images</Label>
              <PromptMessageImageTags
                images={images}
                setImages={setImages}
                align="start"
              />
            </div>
          )}
          {!isEdit && isChatPrompt && (
            <div className="flex flex-col gap-2 pb-4">
              <div className="flex items-center justify-between">
                <Label>Chat messages</Label>
                <Button
                  variant="ghost"
                  size="sm"
                  onClick={() => setShowRawView(!showRawView)}
                >
                  {showRawView ? (
                    <>
                      <MessageSquare className="mr-1.5 size-3.5" />
                      Message view
                    </>
                  ) : (
                    <>
                      <Code2 className="mr-1.5 size-3.5" />
                      Raw view
                    </>
                  )}
                </Button>
              </div>
              {showRawView ? (
                <>
                  <Textarea
                    id="template"
                    className="comet-code min-h-[400px]"
                    placeholder="Chat messages JSON"
                    value={JSON.stringify(
                      messages.map((m) => ({
                        role: m.role,
                        content: m.content,
                      })),
                      null,
                      2,
                    )}
                    onChange={(event) => {
                      try {
                        const parsed = JSON.parse(event.target.value);
                        if (Array.isArray(parsed)) {
                          setMessages(
                            parsed.map((msg, index) => ({
                              id: `msg-${index}`,
                              role: msg.role,
                              content: msg.content,
                            })),
                          );
                        }
                      } catch {
                        // Invalid JSON, don't update
                      }
                    }}
                  />
                  <Description>
                    Edit the raw JSON representation of chat messages. Must be a
                    valid JSON array.
                  </Description>
                </>
              ) : (
                <>
                  <div className="h-[400px] rounded-md border bg-primary-foreground">
                    <LLMPromptMessages
                      messages={messages}
                      onChange={setMessages}
                      onAddMessage={handleAddMessage}
                      hidePromptActions
                    />
                  </div>
                  <Description>
                    Create your chat prompt with multiple messages. Each message
                    has a role (system, user, assistant) and content.
                  </Description>
                </>
              )}
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
                    <Description className="mt-2 block">
                      {
                        EXPLAINERS_MAP[
                          EXPLAINER_ID.what_format_should_the_metadata_be
                        ].description
                      }
                    </Description>
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
