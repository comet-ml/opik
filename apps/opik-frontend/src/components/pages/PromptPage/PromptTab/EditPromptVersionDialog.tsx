import React, { useState, useMemo, useCallback } from "react";
import CodeMirror from "@uiw/react-codemirror";
import { jsonLanguage } from "@codemirror/lang-json";
import { EditorView } from "@codemirror/view";
import { Code2, MessageSquare, Plus } from "lucide-react";

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
import { useBooleanTimeoutState } from "@/hooks/useBooleanTimeoutState";
import { useCodemirrorTheme } from "@/hooks/useCodemirrorTheme";
import { isValidJsonObject, safelyParseJSON } from "@/lib/utils";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";
import { Description } from "@/components/ui/description";
import ExplainerDescription from "@/components/shared/ExplainerDescription/ExplainerDescription";
import { useMessageContent } from "@/hooks/useMessageContent";
import {
  generateDefaultLLMPromptMessage,
  getNextMessageType,
  parseLLMMessageContent,
  parsePromptVersionContent,
  parseChatTemplateToLLMMessages,
} from "@/lib/llm";
import LLMPromptMessages from "@/components/pages-shared/llm/LLMPromptMessages/LLMPromptMessages";
import { LLMMessage } from "@/types/llm";
import ChatPromptRawView from "@/components/pages-shared/llm/ChatPromptRawView/ChatPromptRawView";
import { PROMPT_TEMPLATE_STRUCTURE } from "@/types/prompts";
import TextPromptEditor from "@/components/pages-shared/TextPromptEditor/TextPromptEditor";

type EditPromptVersionDialogProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
  template: string;
  metadata?: object;
  promptName: string;
  templateStructure?: PROMPT_TEMPLATE_STRUCTURE;
  onSetActiveVersionId: (versionId: string) => void;
};

const EditPromptVersionDialog: React.FC<EditPromptVersionDialogProps> = ({
  open,
  setOpen,
  template: promptTemplate,
  metadata: promptMetadata,
  promptName,
  templateStructure,
  onSetActiveVersionId,
}) => {
  const isChatPrompt = templateStructure === PROMPT_TEMPLATE_STRUCTURE.CHAT;

  const metadataString = promptMetadata
    ? JSON.stringify(promptMetadata, null, 2)
    : "";
  const [template, setTemplate] = useState(promptTemplate);
  const [metadata, setMetadata] = useState(metadataString);
  const [changeDescription, setChangeDescription] = useState("");

  // Parse messages from template if it's a chat prompt
  const initialMessages = useMemo<LLMMessage[]>(() => {
    if (!isChatPrompt) return [];

    const parsedMessages = parseChatTemplateToLLMMessages(promptTemplate);

    return parsedMessages.length > 0
      ? parsedMessages
      : [generateDefaultLLMPromptMessage()];
  }, [isChatPrompt, promptTemplate]);

  const [messages, setMessages] = useState<LLMMessage[]>(initialMessages);
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

  const { mutate } = useCreatePromptVersionMutation();

  const handleAddMessage = useCallback(() => {
    setMessages((prev) => {
      const lastMessage = prev[prev.length - 1];
      const nextRole = lastMessage
        ? getNextMessageType(lastMessage)
        : undefined;
      return [...prev, generateDefaultLLMPromptMessage({ role: nextRole })];
    });
  }, []);

  const handleClickEditPrompt = () => {
    const isMetadataValid = metadata === "" || isValidJsonObject(metadata);

    if (!isMetadataValid) {
      return setShowInvalidJSON(true);
    }

    // For chat prompts, serialize messages to JSON
    const finalTemplate = isChatPrompt
      ? JSON.stringify(
          messages.map((msg) => ({
            role: msg.role,
            content: msg.content,
          })),
        )
      : template;

    mutate({
      name: promptName,
      template: finalTemplate,
      changeDescription,
      ...(metadata && { metadata: safelyParseJSON(metadata) }),
      ...(templateStructure && { templateStructure }),
      onSuccess: (data) => {
        onSetActiveVersionId(data.id);
      },
    });

    setOpen(false);
  };

  const templateHasChanges = isChatPrompt
    ? JSON.stringify(
        messages.map((m) => ({ role: m.role, content: m.content })),
      ) !== promptTemplate
    : template !== promptTemplate;
  const metadataHasChanges = metadata !== metadataString;
  const isValid = isChatPrompt
    ? messages.length > 0 &&
      (!showRawView || isRawJsonValid) &&
      (templateHasChanges || metadataHasChanges)
    : template?.length && (templateHasChanges || metadataHasChanges);

  const { images: currentImages, videos: currentVideos } =
    parseLLMMessageContent(
      parsePromptVersionContent({
        template: localText,
        metadata: promptMetadata,
      }),
    );

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-lg sm:max-w-[720px]">
        <DialogHeader>
          <DialogTitle>Edit prompt</DialogTitle>
        </DialogHeader>
        <DialogAutoScrollBody>
          <ExplainerDescription
            className="mb-4"
            {...EXPLAINERS_MAP[EXPLAINER_ID.what_happens_if_i_edit_my_prompt]}
          />
          <div className="flex flex-col gap-2 pb-4">
            <div className="mt-3 flex items-center justify-between gap-0.5">
              <Label htmlFor="promptTemplate">
                {isChatPrompt ? "Chat messages" : "Prompt"}
              </Label>
              {isChatPrompt && (
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
              )}
            </div>
            {isChatPrompt ? (
              showRawView ? (
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
                  <p className="comet-body-s mt-2 text-light-slate">
                    Use mustache syntax to reference dataset variables in your
                    prompt. Example: {"{"}
                    {"{"}question{"}}"}
                    {"}"}.
                  </p>
                </>
              )
            ) : (
              <TextPromptEditor
                value={localText}
                onChange={handleContentChange}
                placeholder="Prompt"
                currentImages={currentImages}
                currentVideos={currentVideos}
              />
            )}
          </div>
          <div className="flex flex-col gap-2 pb-4">
            <Label htmlFor="promptMetadata">Commit message</Label>
            <Textarea
              className="comet-code min-h-20"
              id="promptMetadata"
              value={changeDescription}
              onChange={(e) => setChangeDescription(e.target.value)}
            />
          </div>
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
            Create new commit
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default EditPromptVersionDialog;
