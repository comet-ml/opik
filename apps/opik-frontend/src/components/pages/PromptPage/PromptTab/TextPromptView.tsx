import React, { useMemo, useState } from "react";
import { Code2, MessageSquare } from "lucide-react";
import { Button } from "@/components/ui/button";
import CodeHighlighter from "@/components/shared/CodeHighlighter/CodeHighlighter";
import ChatPromptMessageReadonly from "./ChatPromptMessageReadonly";

interface ChatMessage {
  role: string;
  content: string | Array<{ type: string; [key: string]: unknown }>;
}

interface TextPromptViewProps {
  template: string;
}

const TextPromptView: React.FC<TextPromptViewProps> = ({ template }) => {
  const [showRawView, setShowRawView] = useState(false);

  const messages = useMemo<ChatMessage[]>(() => {
    try {
      const parsed = JSON.parse(template);
      return Array.isArray(parsed) ? parsed : [];
    } catch {
      return [];
    }
  }, [template]);

  const isJsonMessages = messages.length > 0;

  return (
    <div className="flex flex-col gap-1.5">
      <div className="flex items-center justify-between">
        <div className="comet-body-s-accented">
          {isJsonMessages ? "Chat messages" : "Prompt"}
        </div>
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
        <div className="max-h-[600px] overflow-y-auto">
          <CodeHighlighter data={template} />
        </div>
      ) : isJsonMessages ? (
        <div className="flex flex-col gap-2" data-testid="prompt-text-content">
          {messages.map((message, index) => (
            <ChatPromptMessageReadonly
              key={`${message.role}-${index}`}
              message={message}
            />
          ))}
        </div>
      ) : (
        <div
          className="comet-body-s whitespace-pre-wrap break-words rounded-md border bg-primary-foreground p-3 text-foreground"
          data-testid="prompt-text-content"
        >
          {template}
        </div>
      )}
    </div>
  );
};

export default TextPromptView;
