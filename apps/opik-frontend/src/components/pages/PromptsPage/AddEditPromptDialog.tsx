import React, { useCallback, useState } from "react";
import CodeMirror from "@uiw/react-codemirror";
import { EditorView } from "@codemirror/view";
import { jsonLanguage } from "@codemirror/lang-json";
import { useNavigate } from "@tanstack/react-router";
import { Code2, FileText, MessagesSquare, Plus } from "lucide-react";

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
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group";
import { Prompt, PROMPT_TEMPLATE_STRUCTURE } from "@/types/prompts";
import { LLMMessage } from "@/types/llm";
import LLMPromptMessages from "@/components/pages-shared/llm/LLMPromptMessages/LLMPromptMessages";
import { generateDefaultLLMPromptMessage, getNextMessageType } from "@/lib/llm";
import usePromptCreateMutation from "@/api/prompts/usePromptCreateMutation";
import usePromptUpdateMutation from "@/api/prompts/usePromptUpdateMutation";
import { isValidJsonObject, safelyParseJSON } from "@/lib/utils";
import { useCodemirrorTheme } from "@/hooks/useCodemirrorTheme";
import { useBooleanTimeoutState } from "@/hooks/useBooleanTimeoutState";
import useAppStore from "@/store/AppStore";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";
import ExplainerDescription from "@/components/shared/ExplainerDescription/ExplainerDescription";
import { useMessageContent } from "@/hooks/useMessageContent";
import ChatPromptRawView from "@/components/pages-shared/llm/ChatPromptRawView/ChatPromptRawView";
import TextPromptEditor from "@/components/pages-shared/TextPromptEditor/TextPromptEditor";

type AddPromptDialogProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
  prompt?: Prompt;
};

const AddEditPromptDialog: React.FC<AddPromptDialogProps> = ({
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
  const [templateStructure, setTemplateStructure] =
    useState<PROMPT_TEMPLATE_STRUCTURE>(PROMPT_TEMPLATE_STRUCTURE.TEXT);
  const [messages, setMessages] = useState<LLMMessage[]>([
    generateDefaultLLMPromptMessage(),
  ]);
  const [showRawView, setShowRawView] = useState(false);
  const [rawJsonValue, setRawJsonValue] = useState("");
  const [isRawJsonValid, setIsRawJsonValid] = useState(true);

  const [showInvalidJSON, setShowInvalidJSON] = useBooleanTimeoutState({});
  const theme = useCodemirrorTheme({
    editable: true,
  });

  const { localText, handleContentChange } = useMessageContent({
    content: template,
    onChangeContent: (content) => setTemplate(content as string),
  });

  const { mutate: createMutate } = usePromptCreateMutation();
  const { mutate: updateMutate } = usePromptUpdateMutation();

  const isEdit = !!defaultPrompt;
  const isChatPrompt = templateStructure === PROMPT_TEMPLATE_STRUCTURE.CHAT;
  const isValid = Boolean(
    name.length &&
      (isEdit ||
        (isChatPrompt
          ? messages.length > 0 && (!showRawView || isRawJsonValid)
          : template.length)),
  );
  const title = isEdit ? "Edit prompt" : "Create a new prompt";
  const submitText = isEdit ? "Update prompt" : "Create prompt";

  const handleAddMessage = useCallback(() => {
    setMessages((prev) => {
      const lastMessage = prev[prev.length - 1];
      const nextRole = lastMessage
        ? getNextMessageType(lastMessage)
        : undefined;
      return [...prev, generateDefaultLLMPromptMessage({ role: nextRole })];
    });
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

  const createPrompt = () => {
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
          null,
          2,
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
  };

  const editPrompt = () => {
    updateMutate({
      prompt: {
        id: defaultPrompt?.id,
        name,
        ...(description ? { description } : {}),
      },
    });
    setOpen(false);
  };

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
              <ToggleGroup
                type="single"
                variant="ghost"
                value={templateStructure}
                onValueChange={(value) => {
                  if (value) {
                    setTemplateStructure(value as PROMPT_TEMPLATE_STRUCTURE);
                  }
                }}
                className="w-fit justify-start"
              >
                <ToggleGroupItem
                  value={PROMPT_TEMPLATE_STRUCTURE.TEXT}
                  aria-label="Text prompt"
                  className="gap-1.5"
                >
                  <FileText className="size-3.5" />
                  <span>Text prompt</span>
                </ToggleGroupItem>
                <ToggleGroupItem
                  value={PROMPT_TEMPLATE_STRUCTURE.CHAT}
                  aria-label="Chat prompt"
                  className="gap-1.5"
                >
                  <MessagesSquare className="size-3.5" />
                  <span>Chat prompt</span>
                </ToggleGroupItem>
              </ToggleGroup>
              <Description>
                Choose between a simple text prompt or a chat conversation
              </Description>
            </div>
          )}
          {!isEdit && !isChatPrompt && (
            <TextPromptEditor
              value={localText}
              onChange={handleContentChange}
              placeholder="Prompt"
            />
          )}
          {!isEdit && isChatPrompt && (
            <div className="flex flex-col gap-2 pb-4">
              <div className="flex items-center justify-between gap-0.5">
                <Label>Chat messages</Label>
                <Button
                  variant="ghost"
                  size="sm"
                  onClick={() => {
                    const newShowRawView = !showRawView;
                    if (newShowRawView) {
                      setRawJsonValue(
                        JSON.stringify(
                          messages.map((m) => ({
                            role: m.role,
                            content: m.content,
                          })),
                          null,
                          2,
                        ),
                      );
                      // JSON generated from valid messages is always valid
                      setIsRawJsonValid(true);
                    }
                    setShowRawView(newShowRawView);
                  }}
                >
                  {showRawView ? (
                    <>
                      <MessagesSquare className="mr-1.5 size-3.5" />
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
                <ChatPromptRawView
                  value={rawJsonValue}
                  onMessagesChange={setMessages}
                  onRawValueChange={setRawJsonValue}
                  onValidationChange={setIsRawJsonValid}
                />
              ) : (
                <>
                  <LLMPromptMessages
                    messages={messages}
                    onChange={setMessages}
                    onAddMessage={handleAddMessage}
                    hidePromptActions={true}
                    disableMedia={true}
                    hideAddButton={true}
                  />
                  <Button
                    variant="outline"
                    size="sm"
                    className="mt-2 w-fit"
                    onClick={handleAddMessage}
                    type="button"
                  >
                    <Plus className="mr-2 size-4" />
                    Message
                  </Button>
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
