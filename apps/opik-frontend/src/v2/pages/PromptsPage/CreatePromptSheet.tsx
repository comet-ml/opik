import React, { useCallback, useEffect, useMemo, useState } from "react";
import CodeMirror from "@uiw/react-codemirror";
import { EditorView } from "@codemirror/view";
import { jsonLanguage } from "@codemirror/lang-json";
import { useNavigate } from "@tanstack/react-router";
import { Plus } from "lucide-react";

import { Button } from "@/ui/button";
import { Sheet, SheetContent, SheetTopBar } from "@/ui/sheet";
import { Input } from "@/ui/input";
import { Label } from "@/ui/label";
import { Prompt, PROMPT_TEMPLATE_STRUCTURE } from "@/types/prompts";
import { LLMMessage } from "@/types/llm";
import LLMPromptMessages from "@/v2/pages-shared/llm/LLMPromptMessages/LLMPromptMessages";
import ChatPromptRawView from "@/v2/pages-shared/llm/ChatPromptRawView/ChatPromptRawView";
import {
  FormFieldCard,
  FormFieldModeSelect,
} from "@/v2/pages-shared/llm/FormFieldCard";
import CodeBlockCopy from "@/v2/pages-shared/traces/TraceDetailsPanel/TraceDataViewer/CodeBlock/CodeBlockCopy";
import {
  generateDefaultLLMPromptMessage,
  getNextMessageType,
} from "@/lib/llm";
import { serializeChatTemplate } from "@/lib/chatTemplate";
import AutoResizeTextarea from "@/v2/pages-shared/agent-configuration/fields/AutoResizeTextarea";
import usePromptCreateMutation from "@/api/prompts/usePromptCreateMutation";
import { isMessageEmpty } from "@/v2/pages-shared/agent-configuration/useAgentConfigurationSave";
import { isValidJsonObject, safelyParseJSON } from "@/lib/utils";
import { useCodemirrorTheme } from "@/hooks/useCodemirrorTheme";
import { useBooleanTimeoutState } from "@/hooks/useBooleanTimeoutState";
import useAppStore, { useActiveProjectId } from "@/store/AppStore";

type CreatePromptSheetProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
  templateStructure: PROMPT_TEMPLATE_STRUCTURE;
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

const CreatePromptSheet: React.FC<CreatePromptSheetProps> = ({
  open,
  setOpen,
  templateStructure,
}) => {
  const workspaceName = useAppStore((s) => s.activeWorkspaceName);
  const activeProjectId = useActiveProjectId();
  const navigate = useNavigate();
  const theme = useCodemirrorTheme({ editable: true });

  const isChatPrompt = templateStructure === PROMPT_TEMPLATE_STRUCTURE.CHAT;
  const title = isChatPrompt ? "New chat prompt" : "New text prompt";

  const [name, setName] = useState("");
  const [template, setTemplate] = useState("");
  const [metadata, setMetadata] = useState("");
  const [description, setDescription] = useState("");
  const [messages, setMessages] = useState<LLMMessage[]>([
    generateDefaultLLMPromptMessage(),
  ]);
  const [chatViewMode, setChatViewMode] = useState<ChatViewMode>("messages");
  const [chatRaw, setChatRaw] = useState(() =>
    serializeMessagesForRaw([generateDefaultLLMPromptMessage()]),
  );
  const [isChatRawValid, setIsChatRawValid] = useState(true);
  const [showInvalidJSON, setShowInvalidJSON] = useBooleanTimeoutState({});

  const { mutate: createMutate, isPending: isCreating } =
    usePromptCreateMutation();

  const isValid = useMemo(() => {
    if (!name.trim().length) return false;
    if (isChatPrompt) {
      const hasNonEmptyMessage = messages.some((m) => !isMessageEmpty(m));
      return (
        hasNonEmptyMessage && (chatViewMode === "messages" || isChatRawValid)
      );
    }
    return template.trim().length > 0;
  }, [name, isChatPrompt, messages, chatViewMode, isChatRawValid, template]);

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
        // Capture the latest structured messages before showing the raw editor.
        setChatRaw(serializeMessagesForRaw(messages));
        setIsChatRawValid(true);
      }
      setChatViewMode(next);
    },
    [chatViewMode, messages],
  );

  const onPromptCreated = useCallback(
    (prompt: Prompt) => {
      if (!prompt.id) return;
      navigate({
        to: "/$workspaceName/projects/$projectId/prompts/$promptId",
        params: {
          promptId: prompt.id,
          workspaceName,
          projectId: activeProjectId!,
        },
      });
    },
    [navigate, workspaceName, activeProjectId],
  );

  const handleCreate = useCallback(() => {
    if (!isValid || isCreating) return;
    const isMetadataValid = metadata === "" || isValidJsonObject(metadata);
    if (!isMetadataValid) {
      return setShowInvalidJSON(true);
    }

    const promptTemplate = isChatPrompt
      ? serializeChatTemplate(messages)
      : template;

    createMutate(
      {
        prompt: {
          name,
          template: promptTemplate,
          template_structure: templateStructure,
          ...(metadata && { metadata: safelyParseJSON(metadata) }),
          ...(description && { description }),
          project_id: activeProjectId ?? undefined,
        },
      },
      {
        onSuccess: (prompt) => {
          onPromptCreated(prompt);
          setOpen(false);
        },
      },
    );
  }, [
    isValid,
    isCreating,
    metadata,
    isChatPrompt,
    messages,
    template,
    createMutate,
    name,
    templateStructure,
    description,
    activeProjectId,
    onPromptCreated,
    setOpen,
    setShowInvalidJSON,
  ]);

  useEffect(() => {
    if (!open) return;
    const handler = (e: KeyboardEvent) => {
      if (e.key === "Enter" && (e.metaKey || e.ctrlKey)) {
        e.preventDefault();
        handleCreate();
      }
    };
    window.addEventListener("keydown", handler);
    return () => window.removeEventListener("keydown", handler);
  }, [open, handleCreate]);

  return (
    <Sheet open={open} onOpenChange={setOpen}>
      <SheetContent
        side="right"
        className="flex w-full max-w-none flex-col p-0 sm:max-w-[720px]"
        header={<SheetTopBar variant="form" title={title} />}
      >
        <div className="min-h-0 flex-1 space-y-4 overflow-y-auto px-6 pb-6">
          <div className="space-y-1.5">
            <Label htmlFor="promptName">Name</Label>
            <Input
              id="promptName"
              dimension="sm"
              placeholder={title}
              value={name}
              onChange={(e) => setName(e.target.value)}
            />
          </div>

          {!isChatPrompt && (
            <div className="space-y-1.5">
              <FormFieldCard
                title="Prompt"
                actions={<CodeBlockCopy text={template} />}
              >
                <div className="min-h-[120px]">
                  <AutoResizeTextarea
                    value={template}
                    onChange={setTemplate}
                    placeholder="Type your prompt..."
                    className="comet-code"
                  />
                </div>
              </FormFieldCard>
              <p className="comet-body-xs text-light-slate">
                Use mustache syntax to reference test suite variables in your
                prompt. Example: {"{{question}}"}.
              </p>
            </div>
          )}

          {isChatPrompt && (
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
                        ? chatRaw
                        : serializeMessagesForRaw(messages)
                    }
                  />
                </>
              }
            >
              {chatViewMode === "json" ? (
                <ChatPromptRawView
                  value={chatRaw}
                  onMessagesChange={setMessages}
                  onRawValueChange={setChatRaw}
                  onValidationChange={setIsChatRawValid}
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
            <Label htmlFor="versionNotes">Version notes</Label>
            <div className="rounded-md border bg-background">
              <AutoResizeTextarea
                value={description}
                onChange={setDescription}
                placeholder="Add optional description"
                className="comet-body-s min-h-8 px-3 py-1.5"
              />
            </div>
          </div>
        </div>

        <div className="flex items-center justify-end gap-2 border-t px-6 py-3">
          <Button
            variant="outline"
            size="sm"
            onClick={() => setOpen(false)}
            disabled={isCreating}
          >
            Cancel
          </Button>
          <Button
            size="sm"
            disabled={!isValid || isCreating}
            onClick={handleCreate}
          >
            {isCreating ? "Creating…" : "Create prompt"}
          </Button>
        </div>
      </SheetContent>
    </Sheet>
  );
};

export default CreatePromptSheet;
