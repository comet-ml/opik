import React, { useMemo, useState } from "react";
import { Code2, MessageSquare } from "lucide-react";
import { Button } from "@/components/ui/button";
import SyntaxHighlighter from "@/components/shared/SyntaxHighlighter/SyntaxHighlighter";
import PromptMessagesReadonly, {
  ChatMessage,
} from "@/components/pages-shared/llm/PromptMessagesReadonly/PromptMessagesReadonly";

export const parseMessagesFromTemplate = (template: unknown): ChatMessage[] => {
  try {
    const data = typeof template === "string" ? JSON.parse(template) : template;
    return Array.isArray(data) ? data : [];
  } catch {
    return [];
  }
};

interface PromptTemplateViewProps {
  template: unknown;
  templateStructure?: "chat" | "text";
  search?: string;
  children?: React.ReactNode;
}

const PromptTemplateView: React.FC<PromptTemplateViewProps> = ({
  template,
  templateStructure,
  search,
  children,
}) => {
  const [showRawView, setShowRawView] = useState(false);

  const messages = useMemo<ChatMessage[]>(
    () => parseMessagesFromTemplate(template),
    [template],
  );

  const isChatPrompt = templateStructure === "chat";
  const hasMessages = messages.length > 0;

  const parsedTemplate = useMemo(() => {
    try {
      return typeof template === "string" ? JSON.parse(template) : template;
    } catch {
      return template;
    }
  }, [template]);

  const serializedMessages = useMemo(
    () =>
      messages
        .map((m) =>
          typeof m.content === "string" ? m.content : JSON.stringify(m.content),
        )
        .join("\n\n"),
    [messages],
  );

  return (
    <div className="flex flex-col gap-1.5">
      <div className="flex items-center justify-between">
        <div className="comet-body-s-accented">
          {isChatPrompt ? "Chat messages" : "Prompt"}
        </div>
        {hasMessages && (
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
        )}
      </div>

      {showRawView || !hasMessages ? (
        <SyntaxHighlighter
          withSearch={Boolean(search)}
          data={parsedTemplate as object}
          search={search}
        />
      ) : isChatPrompt ? (
        <PromptMessagesReadonly messages={messages} />
      ) : (
        <div className="comet-body-s whitespace-pre-wrap break-words rounded-md border bg-primary-foreground p-3 text-foreground">
          {serializedMessages}
        </div>
      )}

      {children}
    </div>
  );
};

export default PromptTemplateView;
