import React, { useCallback, useMemo, useState } from "react";
import CodeMirror from "@uiw/react-codemirror";
import { EditorView } from "@codemirror/view";
import { jsonLanguage } from "@codemirror/lang-json";
import { useNavigate } from "@tanstack/react-router";
import { ChevronDown, Copy, Plus, Sparkles } from "lucide-react";
import copy from "clipboard-copy";

import { Button } from "@/ui/button";
import { Sheet, SheetContent, SheetTopBar } from "@/ui/sheet";
import { Input } from "@/ui/input";
import { Label } from "@/ui/label";
import { useToast } from "@/ui/use-toast";
import { Prompt, PROMPT_TEMPLATE_STRUCTURE } from "@/types/prompts";
import { LLMMessage } from "@/types/llm";
import LLMPromptMessages from "@/v2/pages-shared/llm/LLMPromptMessages/LLMPromptMessages";
import ChatPromptRawView from "@/v2/pages-shared/llm/ChatPromptRawView/ChatPromptRawView";
import { generateDefaultLLMPromptMessage, getNextMessageType } from "@/lib/llm";
import MarkdownPreview from "@/shared/MarkdownPreview/MarkdownPreview";
import AutoResizeTextarea from "@/v2/pages-shared/agent-configuration/fields/AutoResizeTextarea";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import usePromptCreateMutation from "@/api/prompts/usePromptCreateMutation";
import { isValidJsonObject, safelyParseJSON } from "@/lib/utils";
import { useCodemirrorTheme } from "@/hooks/useCodemirrorTheme";
import { useBooleanTimeoutState } from "@/hooks/useBooleanTimeoutState";
import useAppStore, { useActiveProjectId } from "@/store/AppStore";

type CreatePromptSheetProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
  templateStructure: PROMPT_TEMPLATE_STRUCTURE;
};

type PromptView = "raw" | "pretty";

const CreatePromptSheet: React.FC<CreatePromptSheetProps> = ({
  open,
  setOpen,
  templateStructure,
}) => {
  const workspaceName = useAppStore((s) => s.activeWorkspaceName);
  const activeProjectId = useActiveProjectId();
  const navigate = useNavigate();
  const { toast } = useToast();
  const theme = useCodemirrorTheme({ editable: true });

  const isChatPrompt = templateStructure === PROMPT_TEMPLATE_STRUCTURE.CHAT;
  const title = isChatPrompt ? "New chat prompt" : "New text prompt";

  const [name, setName] = useState("");
  const [template, setTemplate] = useState("");
  const [metadata, setMetadata] = useState("");
  const [description, setDescription] = useState("");
  const [promptView, setPromptView] = useState<PromptView>("raw");
  const [messages, setMessages] = useState<LLMMessage[]>([
    generateDefaultLLMPromptMessage(),
  ]);
  const [showChatRaw, setShowChatRaw] = useState(false);
  const [chatRaw, setChatRaw] = useState("");
  const [isChatRawValid, setIsChatRawValid] = useState(true);
  const [showInvalidJSON, setShowInvalidJSON] = useBooleanTimeoutState({});

  const { mutate: createMutate, isPending: isCreating } =
    usePromptCreateMutation();

  const isValid = useMemo(() => {
    if (!name.length) return false;
    if (isChatPrompt) {
      return messages.length > 0 && (!showChatRaw || isChatRawValid);
    }
    return template.length > 0;
  }, [
    name,
    isChatPrompt,
    messages.length,
    showChatRaw,
    isChatRawValid,
    template,
  ]);

  const handleCopyPrompt = useCallback(async () => {
    if (!template) return;
    await copy(template);
    toast({ description: "Prompt copied to clipboard" });
  }, [template, toast]);

  const handleCopyMetadata = useCallback(async () => {
    if (!metadata) return;
    await copy(metadata);
    toast({ description: "Metadata copied to clipboard" });
  }, [metadata, toast]);

  const handleAddMessage = useCallback(() => {
    setMessages((prev) => {
      const last = prev[prev.length - 1];
      const nextRole = last ? getNextMessageType(last) : undefined;
      return [...prev, generateDefaultLLMPromptMessage({ role: nextRole })];
    });
  }, []);

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

  const handleCreate = () => {
    const isMetadataValid = metadata === "" || isValidJsonObject(metadata);
    if (!isMetadataValid) {
      return setShowInvalidJSON(true);
    }

    const promptTemplate = isChatPrompt
      ? JSON.stringify(
          messages.map((m) => ({ role: m.role, content: m.content })),
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
  };

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
              <Label>Prompt</Label>
              <div className="rounded-md border bg-soft-background">
                <div className="flex items-center justify-between border-b px-3 py-1.5">
                  <Button
                    variant="ghost"
                    size="2xs"
                    onClick={() =>
                      setPromptView((v) => (v === "pretty" ? "raw" : "pretty"))
                    }
                  >
                    {promptView === "pretty" ? (
                      <>
                        Pretty <Sparkles className="ml-1 size-3" />
                      </>
                    ) : (
                      <>Raw</>
                    )}
                    <ChevronDown className="ml-1 size-3" />
                  </Button>
                  <TooltipWrapper content="Copy prompt">
                    <Button
                      variant="minimal"
                      size="icon-2xs"
                      onClick={handleCopyPrompt}
                    >
                      <Copy />
                    </Button>
                  </TooltipWrapper>
                </div>
                <div className="min-h-[120px] p-3">
                  {promptView === "pretty" ? (
                    template ? (
                      <MarkdownPreview className="prose-sm">
                        {template}
                      </MarkdownPreview>
                    ) : (
                      <span className="comet-body-s text-light-slate">
                        Type your prompt...
                      </span>
                    )
                  ) : (
                    <AutoResizeTextarea
                      value={template}
                      onChange={setTemplate}
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

          {isChatPrompt && (
            <div className="space-y-1.5">
              <Label>Chat messages</Label>
              <div className="rounded-md border bg-soft-background">
                <div className="flex items-center justify-between border-b px-3 py-1.5">
                  <Button
                    variant="ghost"
                    size="2xs"
                    onClick={() => {
                      const next = !showChatRaw;
                      if (next) {
                        setChatRaw(
                          JSON.stringify(
                            messages.map((m) => ({
                              role: m.role,
                              content: m.content,
                            })),
                            null,
                            2,
                          ),
                        );
                        setIsChatRawValid(true);
                      }
                      setShowChatRaw(next);
                    }}
                  >
                    {showChatRaw ? (
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
                  {showChatRaw ? (
                    <ChatPromptRawView
                      value={chatRaw}
                      onMessagesChange={setMessages}
                      onRawValueChange={setChatRaw}
                      onValidationChange={setIsChatRawValid}
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
              {showChatRaw && (
                <p className="comet-body-xs text-light-slate">
                  Edit chat messages as raw JSON. Must be a valid array with at
                  least one message containing a role and content.
                </p>
              )}
            </div>
          )}

          <div className="space-y-1.5">
            <Label>Metadata</Label>
            <div className="rounded-md border bg-soft-background">
              <div className="flex items-center justify-between border-b py-1.5 pl-6 pr-3">
                <span className="comet-body-xs uppercase tracking-wide text-foreground">
                  JSON
                </span>
                <TooltipWrapper content="Copy metadata">
                  <Button
                    variant="minimal"
                    size="icon-2xs"
                    onClick={handleCopyMetadata}
                  >
                    <Copy />
                  </Button>
                </TooltipWrapper>
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
            <Label htmlFor="versionNotes">Version notes</Label>
            <div className="min-h-8 rounded-md border bg-background px-3 py-1.5">
              <AutoResizeTextarea
                value={description}
                onChange={setDescription}
                placeholder="Add optional description"
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
