import React from "react";
import { LucideIcon, Sparkles } from "lucide-react";

import { Separator } from "@/ui/separator";
import {
  FormFieldCard,
  FormFieldModeSelect,
} from "@/v2/pages-shared/llm/FormFieldCard";
import LLMPromptMessages from "@/v2/pages-shared/llm/LLMPromptMessages/LLMPromptMessages";
import ChatPromptRawView from "@/v2/pages-shared/llm/ChatPromptRawView/ChatPromptRawView";
import AutoResizeTextarea from "@/v2/pages-shared/agent-configuration/fields/AutoResizeTextarea";
import CodeBlockCopy from "@/v2/pages-shared/traces/TraceDetailsPanel/TraceDataViewer/CodeBlock/CodeBlockCopy";
import { cn } from "@/lib/utils";
import {
  ChatViewMode,
  PromptTemplateEditorState,
} from "./usePromptTemplateEditor";

const CHAT_VIEW_OPTIONS: Array<{
  value: ChatViewMode;
  label: string;
  icon?: LucideIcon;
}> = [
  { value: "pretty", label: "Pretty", icon: Sparkles },
  { value: "json", label: "JSON" },
];

type PromptTemplateEditorProps = {
  editor: PromptTemplateEditorState;
  textMinHeightClassName?: string;
};

/**
 * Renders the prompt-template editor card used by both CreatePromptSheet and
 * EditPromptSheet. State lives in [usePromptTemplateEditor]; this component is
 * the pure rendering layer.
 */
const PromptTemplateEditor: React.FC<PromptTemplateEditorProps> = ({
  editor,
  textMinHeightClassName,
}) => {
  if (editor.isChatPrompt) {
    return (
      <FormFieldCard
        title="Chat messages"
        actions={
          <>
            <FormFieldModeSelect
              value={editor.chatViewMode}
              options={CHAT_VIEW_OPTIONS}
              onChange={editor.onChatViewModeChange}
            />
            <Separator orientation="vertical" className="-ml-2 h-3" />
            <CodeBlockCopy text={editor.copyableRaw} />
          </>
        }
      >
        {editor.chatViewMode === "json" ? (
          <ChatPromptRawView
            value={editor.rawJsonValue}
            onMessagesChange={editor.setMessages}
            onRawValueChange={editor.setRawJsonValue}
            onValidationChange={editor.setIsRawJsonValid}
            bare
          />
        ) : (
          <LLMPromptMessages
            messages={editor.messages}
            onChange={editor.setMessages}
            onAddMessage={editor.handleAddMessage}
            hidePromptActions
            disableMedia
          />
        )}
      </FormFieldCard>
    );
  }

  return (
    <div className="space-y-1.5">
      <FormFieldCard
        title="Prompt"
        actions={<CodeBlockCopy text={editor.template} />}
      >
        <div className={cn(textMinHeightClassName)}>
          <AutoResizeTextarea
            value={editor.template}
            onChange={editor.setTemplate}
            placeholder="Type your prompt..."
            className="comet-code"
          />
        </div>
      </FormFieldCard>
      <p className="comet-body-xs text-light-slate">
        Use mustache syntax to reference test suite variables in your prompt.
        Example: {"{{question}}"}.
      </p>
    </div>
  );
};

export default PromptTemplateEditor;
