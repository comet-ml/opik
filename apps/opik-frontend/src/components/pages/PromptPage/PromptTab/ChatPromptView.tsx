import React, { useMemo, useState } from "react";
import { Code2, MessageSquare } from "lucide-react";
import { Button } from "@/components/ui/button";
import CodeHighlighter, {
  SUPPORTED_LANGUAGE,
} from "@/components/shared/CodeHighlighter/CodeHighlighter";
import ChatPromptMessageReadonly from "./ChatPromptMessageReadonly";

interface ChatMessage {
  role: string;
  content: string | Array<{ type: string; [key: string]: unknown }>;
}

interface ChatPromptViewProps {
  template: string;
}

const ChatPromptView: React.FC<ChatPromptViewProps> = ({ template }) => {
  const [showRawView, setShowRawView] = useState(false);

  const messages = useMemo<ChatMessage[]>(() => {
    try {
      const parsed = JSON.parse(template);
      return Array.isArray(parsed) ? parsed : [];
    } catch {
      return [];
    }
  }, [template]);

  if (messages.length === 0) {
    return (
      <div className="flex items-center justify-center p-8 text-muted-foreground">
        <p>No messages found in chat prompt</p>
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-3">
      <div className="flex items-center justify-between">
        <div className="text-sm text-muted-foreground">
          {showRawView
            ? "Raw JSON"
            : `${messages.length} message${messages.length !== 1 ? "s" : ""}`}
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
          <CodeHighlighter
            data={JSON.stringify(messages, null, 2)}
            language={SUPPORTED_LANGUAGE.json}
          />
        </div>
      ) : (
        <div className="flex flex-col gap-2">
          {messages.map((message, index) => (
            <ChatPromptMessageReadonly key={index} message={message} />
          ))}
        </div>
      )}
    </div>
  );
};

export default ChatPromptView;
