import React, {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import CodeMirror from "@uiw/react-codemirror";
import { jsonLanguage } from "@codemirror/lang-json";
import { EditorView } from "@codemirror/view";
import { Plus } from "lucide-react";

import { Sheet, SheetContent, SheetTopBar } from "@/ui/sheet";
import { Label } from "@/ui/label";
import { Button } from "@/ui/button";
import AutoResizeTextarea from "@/v2/pages-shared/agent-configuration/fields/AutoResizeTextarea";
import {
  FormFieldCard,
  FormFieldModeSelect,
} from "@/v2/pages-shared/llm/FormFieldCard";
import CodeBlockCopy from "@/v2/pages-shared/traces/TraceDetailsPanel/TraceDataViewer/CodeBlock/CodeBlockCopy";
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
import {
  normalizeChatTemplate,
  serializeChatTemplate,
} from "@/lib/chatTemplate";
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

type ChatViewMode = "messages" | "json";

const CHAT_VIEW_OPTIONS: Array<{ value: ChatViewMode; label: string }> = [
  { value: "messages", label: "Messages" },
  { value: "json", label: "JSON" },
];

const serializeMessagesForRaw = (messages: LLMMessage[]): string =>
  JSON.stringify(
    messages.map((m) => ({ role: m.role, content: m.content })),
    null,
    2,
  );

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
  const [chatViewMode, setChatViewMode] = useState<ChatViewMode>("messages");
  const [rawJsonValue, setRawJsonValue] = useState(() =>
    isChatPrompt ? normalizeChatTemplate(promptTemplate) : "",
  );
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
    setRawJsonValue(
      isChatPrompt ? normalizeChatTemplate(props.promptTemplate) : "",
    );
    setIsRawJsonValid(true);
    setChatViewMode("messages");
  }, [open, isChatPrompt]);

  const [showInvalidJSON, setShowInvalidJSON] = useBooleanTimeoutState({});
  const theme = useCodemirrorTheme({
    editable: true,
  });

  const { localText, handleContentChange } = useMessageContent({
    content: template,
    onChangeContent: (content) => setTemplate(content as string),
  });

  const { mutate, isPending: isSaving } = useCreatePromptVersionMutation();

  const handleAddMessage = useCallback(() => {
    setMessages((prev) => {
      const last = prev[prev.length - 1];
      const nextRole = last ? getNextMessageType(last) : undefined;
      return [...prev, generateDefaultLLMPromptMessage({ role: nextRole })];
    });
  }, []);

  const handleSwitchChatView = useCallback(
    (next: ChatViewMode) => {
      if (next === chatViewMode) return;
      if (next === "json") {
        setRawJsonValue(serializeMessagesForRaw(messages));
        setIsRawJsonValid(true);
      }
      setChatViewMode(next);
    },
    [chatViewMode, messages],
  );

  const templateHasChanges = isChatPrompt
    ? serializeChatTemplate(messages) !== promptTemplate
    : template !== promptTemplate;
  const metadataHasChanges = metadata !== metadataString;
  const isValid = isChatPrompt
    ? messages.length > 0 &&
      (chatViewMode === "messages" || isRawJsonValid) &&
      (templateHasChanges || metadataHasChanges)
    : (template?.length ?? 0) > 0 &&
      (templateHasChanges || metadataHasChanges);

  const handleClickEditPrompt = useCallback(() => {
    if (!isValid || isSaving) return;
    const isMetadataValid = metadata === "" || isValidJsonObject(metadata);

    if (!isMetadataValid) {
      return setShowInvalidJSON(true);
    }

    const finalTemplate = isChatPrompt
      ? serializeChatTemplate(messages)
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
  }, [
    isValid,
    isSaving,
    metadata,
    isChatPrompt,
    messages,
    template,
    mutate,
    promptName,
    changeDescription,
    templateStructure,
    promptType,
    activeProjectId,
    onSetActiveVersionId,
    setOpen,
    setShowInvalidJSON,
  ]);

  useEffect(() => {
    if (!open) return;
    const handler = (e: KeyboardEvent) => {
      if (e.key === "Enter" && (e.metaKey || e.ctrlKey)) {
        e.preventDefault();
        handleClickEditPrompt();
      }
    };
    window.addEventListener("keydown", handler);
    return () => window.removeEventListener("keydown", handler);
  }, [open, handleClickEditPrompt]);

  return (
    <Sheet open={open} onOpenChange={setOpen}>
      <SheetContent
        side="right"
        className="flex w-full max-w-none flex-col p-0 sm:max-w-[720px]"
        header={<SheetTopBar variant="form" title="Edit prompt" />}
      >
        <div className="min-h-0 flex-1 space-y-4 overflow-y-auto px-6 pb-6">
          {isChatPrompt ? (
            <FormFieldCard
              title="Chat messages"
              actions={
                <>
                  <FormFieldModeSelect
                    value={chatViewMode}
                    options={CHAT_VIEW_OPTIONS}
                    onChange={handleSwitchChatView}
                  />
                  <CodeBlockCopy
                    text={
                      chatViewMode === "json"
                        ? rawJsonValue
                        : serializeMessagesForRaw(messages)
                    }
                  />
                </>
              }
            >
              {chatViewMode === "json" ? (
                <ChatPromptRawView
                  value={rawJsonValue}
                  onMessagesChange={setMessages}
                  onRawValueChange={setRawJsonValue}
                  onValidationChange={setIsRawJsonValid}
                  bare
                />
              ) : (
                <>
                  <LLMPromptMessages
                    messages={messages}
                    onChange={setMessages}
                    onAddMessage={handleAddMessage}
                    hidePromptActions
                    disableMedia
                    hideAddButton
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
            </FormFieldCard>
          ) : (
            <div className="space-y-1.5">
              <FormFieldCard
                title="Prompt"
                actions={<CodeBlockCopy text={localText} />}
              >
                <AutoResizeTextarea
                  value={localText}
                  onChange={handleContentChange}
                  placeholder="Type your prompt..."
                  className="comet-code"
                />
              </FormFieldCard>
              <p className="comet-body-xs text-light-slate">
                Use mustache syntax to reference test suite variables in your
                prompt. Example: {"{{question}}"}.
              </p>
            </div>
          )}

          <div className="space-y-1.5">
            <FormFieldCard
              title="Metadata"
              actions={<CodeBlockCopy text={metadata} />}
              bodyClassName="px-0 pt-2"
            >
              <div className="max-h-60 overflow-y-auto">
                <CodeMirror
                  theme={theme}
                  value={metadata}
                  onChange={setMetadata}
                  extensions={[jsonLanguage, EditorView.lineWrapping]}
                />
              </div>
            </FormFieldCard>
            {showInvalidJSON && (
              <p className="comet-body-s text-destructive">
                Metadata field is not valid
              </p>
            )}
          </div>

          <div className="space-y-1.5">
            <Label htmlFor="promptVersionNotes">Version notes</Label>
            <div className="rounded-md border bg-background">
              <AutoResizeTextarea
                value={changeDescription}
                onChange={setChangeDescription}
                placeholder="Describe what changed in this version"
                className="comet-body-s min-h-8 px-3 py-1.5"
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
            disabled={!isValid || isSaving}
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
