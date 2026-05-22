import React, {
  useState,
  useMemo,
  useCallback,
  useEffect,
  useRef,
} from "react";
import CodeMirror from "@uiw/react-codemirror";
import { jsonLanguage } from "@codemirror/lang-json";
import { EditorView } from "@codemirror/view";
import { ChevronDown, Plus, Sparkles } from "lucide-react";

import { Sheet, SheetContent, SheetTopBar } from "@/ui/sheet";
import { Label } from "@/ui/label";
import { Button } from "@/ui/button";
import MarkdownPreview from "@/shared/MarkdownPreview/MarkdownPreview";
import AutoResizeTextarea from "@/v2/pages-shared/agent-configuration/fields/AutoResizeTextarea";
import { useActiveProjectId } from "@/store/AppStore";
import useCreatePromptVersionMutation from "@/api/prompts/useCreatePromptVersionMutation";
import { useBooleanTimeoutState } from "@/hooks/useBooleanTimeoutState";
import { useCodemirrorTheme } from "@/hooks/useCodemirrorTheme";
import { isValidJsonObject, safelyParseJSON } from "@/lib/utils";
import { useMessageContent } from "@/hooks/useMessageContent";
import {
  generateDefaultLLMPromptMessage,
  getNextMessageType,
  parseChatTemplateToLLMMessages,
} from "@/lib/llm";
import LLMPromptMessages from "@/v2/pages-shared/llm/LLMPromptMessages/LLMPromptMessages";
import { LLMMessage } from "@/types/llm";
import ChatPromptRawView from "@/v2/pages-shared/llm/ChatPromptRawView/ChatPromptRawView";
import { PROMPT_TEMPLATE_STRUCTURE, PROMPT_TYPE } from "@/types/prompts";

type EditPromptSheetProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
  template: string;
  metadata?: object;
  promptName: string;
  templateStructure?: PROMPT_TEMPLATE_STRUCTURE;
  type?: PROMPT_TYPE;
  onSetActiveVersionId: (versionId: string) => void;
};

const EditPromptSheet: React.FC<EditPromptSheetProps> = ({
  open,
  setOpen,
  template: promptTemplate,
  metadata: promptMetadata,
  promptName,
  templateStructure,
  type: promptType,
  onSetActiveVersionId,
}) => {
  const activeProjectId = useActiveProjectId();
  const isChatPrompt = templateStructure === PROMPT_TEMPLATE_STRUCTURE.CHAT;

  const [viewMode, setViewMode] = useState<"pretty" | "raw">("raw");

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

  // Reset all editor state to the latest props each time the sheet opens.
  // useState only seeds initial values on mount; without this, reopening
  // after a version switch would show stale draft state.
  const latestPropsRef = useRef({
    promptTemplate,
    metadataString,
    initialMessages,
  });
  latestPropsRef.current = { promptTemplate, metadataString, initialMessages };
  useEffect(() => {
    if (!open) return;
    const props = latestPropsRef.current;
    setTemplate(props.promptTemplate);
    setMetadata(props.metadataString);
    setMessages(props.initialMessages);
    setChangeDescription("");
    setShowRawView(false);
    setRawJsonValue("");
    setIsRawJsonValid(true);
    setViewMode("raw");
  }, [open]);

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
      projectId: activeProjectId ?? undefined,
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

  return (
    <Sheet open={open} onOpenChange={setOpen}>
      <SheetContent
        side="right"
        className="flex w-full max-w-none flex-col p-0 sm:max-w-[720px]"
        header={<SheetTopBar variant="form" title="Edit prompt" />}
      >
        <div className="min-h-0 flex-1 space-y-4 overflow-y-auto px-6 pb-6">
          {isChatPrompt ? (
            <div className="space-y-1.5">
              <Label>Chat messages</Label>
              <div className="rounded-md border bg-soft-background">
                <div className="flex items-center justify-between border-b px-3 py-1.5">
                  <Button
                    variant="ghost"
                    size="2xs"
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
                        setIsRawJsonValid(true);
                      }
                      setShowRawView(newShowRawView);
                    }}
                  >
                    {showRawView ? (
                      <>JSON</>
                    ) : (
                      <>
                        Pretty <Sparkles className="ml-1 size-3" />
                      </>
                    )}
                    <ChevronDown className="ml-1 size-3" />
                  </Button>
                </div>
                <div className="p-3">
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
              </div>
              <p className="comet-body-xs text-light-slate">
                {showRawView
                  ? "Edit chat messages as raw JSON. Must be a valid array with at least one message containing a role and content."
                  : "Use mustache syntax to reference dataset variables in your prompt. Example: {{question}}."}
              </p>
            </div>
          ) : (
            <div className="space-y-1.5">
              <Label>Prompt</Label>
              <div className="rounded-md border bg-soft-background">
                <div className="flex items-center justify-between border-b px-3 py-1.5">
                  <Button
                    variant="ghost"
                    size="2xs"
                    onClick={() =>
                      setViewMode((m) => (m === "pretty" ? "raw" : "pretty"))
                    }
                  >
                    {viewMode === "pretty" ? (
                      <>
                        Pretty <Sparkles className="ml-1 size-3" />
                      </>
                    ) : (
                      <>Raw</>
                    )}
                    <ChevronDown className="ml-1 size-3" />
                  </Button>
                </div>
                <div className="min-h-[120px] p-3">
                  {viewMode === "pretty" ? (
                    localText ? (
                      <MarkdownPreview className="prose-sm">
                        {localText}
                      </MarkdownPreview>
                    ) : (
                      <span className="comet-body-s text-light-slate">
                        Type your prompt...
                      </span>
                    )
                  ) : (
                    <AutoResizeTextarea
                      value={localText}
                      onChange={handleContentChange}
                      placeholder="Type your prompt..."
                      className="comet-body-s"
                    />
                  )}
                </div>
              </div>
              <p className="comet-body-xs text-light-slate">
                {
                  "Use mustache syntax to reference test suite variables in your prompt. Example: {{question}}."
                }
              </p>
            </div>
          )}
          <div className="space-y-1.5">
            <Label>Metadata</Label>
            <div className="rounded-md border bg-soft-background">
              <div className="flex items-center justify-between border-b py-1.5 pl-6 pr-3">
                <span className="comet-body-xs uppercase tracking-wide text-foreground">
                  JSON
                </span>
              </div>
              <div className="max-h-60 overflow-y-auto">
                <CodeMirror
                  theme={theme}
                  value={metadata}
                  onChange={setMetadata}
                  extensions={[jsonLanguage, EditorView.lineWrapping]}
                />
              </div>
            </div>
            {showInvalidJSON && (
              <p className="comet-body-s text-destructive">
                Metadata field is not valid
              </p>
            )}
          </div>

          <div className="space-y-1.5">
            <Label htmlFor="promptCommitMessage">Commit message</Label>
            <div className="min-h-8 rounded-md border bg-background px-3 py-1.5">
              <AutoResizeTextarea
                value={changeDescription}
                onChange={setChangeDescription}
                placeholder="Describe what changed in this version"
              />
            </div>
          </div>
        </div>
        <div className="flex items-center justify-end gap-2 border-t px-6 py-3">
          <Button variant="outline" size="sm" onClick={() => setOpen(false)}>
            Cancel
          </Button>
          <Button
            size="sm"
            type="submit"
            disabled={!isValid}
            onClick={handleClickEditPrompt}
          >
            Create new commit
          </Button>
        </div>
      </SheetContent>
    </Sheet>
  );
};

export default EditPromptSheet;
