import React, { useState, useMemo, useCallback } from "react";
import CodeMirror from "@uiw/react-codemirror";
import { jsonLanguage } from "@codemirror/lang-json";
import { EditorView } from "@codemirror/view";
import { Code2, MessageSquare, Plus } from "lucide-react";
import isEqual from "fast-deep-equal";

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
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group";
import {
  Accordion,
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
} from "@/components/ui/accordion";
import TextDiff from "@/components/shared/CodeDiff/TextDiff";
import MediaTagsList from "@/components/pages-shared/llm/PromptMessageMediaTags/MediaTagsList";
import MarkdownPreview from "@/components/shared/MarkdownPreview/MarkdownPreview";
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
import { PROMPT_TEMPLATE_STRUCTURE, PROMPT_TYPE } from "@/types/prompts";

enum PROMPT_PREVIEW_MODE {
  write = "write",
  prettyView = "prettyView",
  diff = "diff",
}

type EditPromptVersionDialogProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
  template: string;
  metadata?: object;
  promptName: string;
  templateStructure?: PROMPT_TEMPLATE_STRUCTURE;
  type?: PROMPT_TYPE;
  onSetActiveVersionId: (versionId: string) => void;
};

const EditPromptVersionDialog: React.FC<EditPromptVersionDialogProps> = ({
  open,
  setOpen,
  template: promptTemplate,
  metadata: promptMetadata,
  promptName,
  templateStructure,
  type: promptType,
  onSetActiveVersionId,
}) => {
  const isChatPrompt = templateStructure === PROMPT_TEMPLATE_STRUCTURE.CHAT;

  const [previewMode, setPreviewMode] = useState<PROMPT_PREVIEW_MODE>(
    PROMPT_PREVIEW_MODE.write,
  );

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
      ...(promptType && { type: promptType }),
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

  const originalText = promptTemplate;
  const {
    images: originalImages,
    videos: originalVideos,
    audios: originalAudios,
  } = useMemo(
    () =>
      parseLLMMessageContent(
        parsePromptVersionContent({
          template: promptTemplate,
          metadata: promptMetadata,
        }),
      ),
    [promptTemplate, promptMetadata],
  );

  const currentText = template;
  const currentMetadata = useMemo(() => {
    if (!metadata) return undefined;
    const parsed = safelyParseJSON(metadata);
    return isValidJsonObject(parsed) ? parsed : undefined;
  }, [metadata]);

  const {
    images: currentImages,
    videos: currentVideos,
    audios: currentAudios,
  } = parseLLMMessageContent(
    parsePromptVersionContent({
      template: localText,
      metadata: currentMetadata,
    }),
  );

  const imagesHaveChanges = !isEqual(originalImages, currentImages);
  const videosHaveChanges = !isEqual(originalVideos, currentVideos);
  const audiosHaveChanges = !isEqual(originalAudios, currentAudios);

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
          {isChatPrompt ? (
            <div className="flex flex-col gap-2 pb-4">
              <div className="mt-3 flex items-center justify-between gap-0.5">
                <Label htmlFor="promptTemplate">Chat messages</Label>
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
                  <p className="comet-body-s mt-2 text-light-slate">
                    Use mustache syntax to reference dataset variables in your
                    prompt. Example: {"{"}
                    {"{"}question{"}}"}
                    {"}"}.
                  </p>
                </>
              )}
            </div>
          ) : (
            <div className="flex flex-col gap-2 pb-4">
              <div className="mt-3 flex items-center justify-between gap-0.5">
                <Label htmlFor="promptTemplate">Prompt</Label>
                <ToggleGroup
                  type="single"
                  value={previewMode}
                  onValueChange={(value) =>
                    value && setPreviewMode(value as PROMPT_PREVIEW_MODE)
                  }
                  size="sm"
                >
                  <ToggleGroupItem
                    value={PROMPT_PREVIEW_MODE.write}
                    aria-label="Write"
                  >
                    Write
                  </ToggleGroupItem>
                  <ToggleGroupItem
                    value={PROMPT_PREVIEW_MODE.prettyView}
                    aria-label="Pretty view"
                  >
                    Pretty view
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
                <>
                  <Textarea
                    id="template"
                    className="comet-code"
                    placeholder="Prompt"
                    value={localText}
                    onChange={(event) =>
                      handleContentChange(event.target.value)
                    }
                  />
                  <Description>
                    {
                      EXPLAINERS_MAP[
                        EXPLAINER_ID.what_format_should_the_prompt_be
                      ].description
                    }
                  </Description>
                </>
              ) : previewMode === PROMPT_PREVIEW_MODE.prettyView ? (
                <div className="min-h-44 rounded-md border border-border bg-primary-foreground p-3">
                  <MarkdownPreview>{localText}</MarkdownPreview>
                </div>
              ) : (
                <div className="flex flex-col gap-4">
                  <div className="comet-code min-h-44 overflow-y-auto whitespace-pre-line break-words rounded-md border px-2.5 py-1.5">
                    <TextDiff content1={originalText} content2={currentText} />
                  </div>
                  {(imagesHaveChanges ||
                    videosHaveChanges ||
                    audiosHaveChanges) && (
                    <div className="flex flex-col gap-3 rounded-md border p-4">
                      <div className="comet-body-s-accented text-muted-foreground">
                        Media comparison
                      </div>
                      {imagesHaveChanges && (
                        <div className="flex gap-6">
                          <div className="flex flex-1 flex-col gap-2">
                            <div className="comet-body-xs text-muted-foreground">
                              Images before:
                            </div>
                            <MediaTagsList
                              type="image"
                              items={originalImages}
                              editable={false}
                            />
                          </div>
                          <div className="flex flex-1 flex-col gap-2">
                            <div className="comet-body-xs text-muted-foreground">
                              Images after:
                            </div>
                            <MediaTagsList
                              type="image"
                              items={currentImages}
                              editable={false}
                            />
                          </div>
                        </div>
                      )}
                      {videosHaveChanges && (
                        <div className="flex gap-6">
                          <div className="flex flex-1 flex-col gap-2">
                            <div className="comet-body-xs text-muted-foreground">
                              Videos before:
                            </div>
                            <MediaTagsList
                              type="video"
                              items={originalVideos}
                              editable={false}
                            />
                          </div>
                          <div className="flex flex-1 flex-col gap-2">
                            <div className="comet-body-xs text-muted-foreground">
                              Videos after:
                            </div>
                            <MediaTagsList
                              type="video"
                              items={currentVideos}
                              editable={false}
                            />
                          </div>
                        </div>
                      )}
                      {audiosHaveChanges && (
                        <div className="flex gap-6">
                          <div className="flex flex-1 flex-col gap-2">
                            <div className="comet-body-xs text-muted-foreground">
                              Audios before:
                            </div>
                            <MediaTagsList
                              type="audio"
                              items={originalAudios}
                              editable={false}
                            />
                          </div>
                          <div className="flex flex-1 flex-col gap-2">
                            <div className="comet-body-xs text-muted-foreground">
                              Audios after:
                            </div>
                            <MediaTagsList
                              type="audio"
                              items={currentAudios}
                              editable={false}
                            />
                          </div>
                        </div>
                      )}
                    </div>
                  )}
                </div>
              )}
            </div>
          )}
          {!isChatPrompt && previewMode === PROMPT_PREVIEW_MODE.write && (
            <>
              {currentImages.length > 0 && (
                <div className="flex flex-col gap-2 pb-4">
                  <Label>Images</Label>
                  <MediaTagsList
                    type="image"
                    items={currentImages}
                    editable={false}
                    preview={true}
                  />
                </div>
              )}
              {currentVideos.length > 0 && (
                <div className="flex flex-col gap-2 pb-4">
                  <Label>Videos</Label>
                  <MediaTagsList
                    type="video"
                    items={currentVideos}
                    editable={false}
                    preview={true}
                  />
                </div>
              )}
              {currentAudios.length > 0 && (
                <div className="flex flex-col gap-2 pb-4">
                  <Label>Audios</Label>
                  <MediaTagsList
                    type="audio"
                    items={currentAudios}
                    editable={false}
                    preview={true}
                  />
                </div>
              )}
            </>
          )}
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
