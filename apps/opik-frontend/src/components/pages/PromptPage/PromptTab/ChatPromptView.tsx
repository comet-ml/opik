import React, { useMemo, useState } from "react";
import { MessageCircle, User, Bot, Code2, MessageSquare } from "lucide-react";
import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import CodeHighlighter, {
  SUPPORTED_LANGUAGE,
} from "@/components/shared/CodeHighlighter/CodeHighlighter";

interface ChatMessage {
  role: string;
  content: string | Array<{ type: string; [key: string]: unknown }>;
}

interface ChatPromptViewProps {
  template: string;
}

const ChatPromptView: React.FunctionComponent<ChatPromptViewProps> = ({
  template,
}) => {
  const [showRawView, setShowRawView] = useState(false);

  const messages = useMemo<ChatMessage[]>(() => {
    try {
      const parsed = JSON.parse(template);
      return Array.isArray(parsed) ? parsed : [];
    } catch {
      return [];
    }
  }, [template]);

  const getMessageContent = (
    content: string | Array<{ type: string; [key: string]: unknown }>,
  ): string => {
    if (typeof content === "string") {
      return content;
    }
    // Handle multimodal content
    if (Array.isArray(content)) {
      return content
        .filter((part) => part.type === "text")
        .map((part) => part.text || "")
        .join("\n");
    }
    return "";
  };

  const getRoleIcon = (role: string) => {
    switch (role.toLowerCase()) {
      case "system":
        return <MessageCircle className="size-4" />;
      case "user":
        return <User className="size-4" />;
      case "assistant":
        return <Bot className="size-4" />;
      default:
        return <MessageCircle className="size-4" />;
    }
  };

  const getRoleLabel = (role: string) => {
    return role.charAt(0).toUpperCase() + role.slice(1);
  };

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
        <CodeHighlighter
          data={JSON.stringify(messages, null, 2)}
          language={SUPPORTED_LANGUAGE.json}
        />
      ) : (
        <>
          {messages.map((message, index) => {
            const role = message.role.toLowerCase();
            const isSystem = role === "system";
            const isUser = role === "user";
            const isAssistant = role === "assistant";

            return (
              <div
                key={index}
                className={cn(
                  "group relative flex flex-col gap-2.5 rounded-lg border p-4 shadow-sm transition-all hover:shadow-md",
                  isSystem && "border-orange-200 bg-orange-50/50",
                  isUser && "border-blue-200 bg-blue-50/50",
                  isAssistant && "border-green-200 bg-green-50/50",
                  !isSystem &&
                    !isUser &&
                    !isAssistant &&
                    "border-gray-200 bg-gray-50/50",
                )}
              >
                <div className="flex items-center gap-2">
                  <div
                    className={cn(
                      "flex size-7 items-center justify-center rounded-full shadow-sm",
                      isSystem && "bg-orange-100 text-orange-700",
                      isUser && "bg-blue-100 text-blue-700",
                      isAssistant && "bg-green-100 text-green-700",
                      !isSystem &&
                        !isUser &&
                        !isAssistant &&
                        "bg-gray-100 text-gray-700",
                    )}
                  >
                    {getRoleIcon(message.role)}
                  </div>
                  <span
                    className={cn(
                      "text-sm font-semibold",
                      isSystem && "text-orange-900",
                      isUser && "text-blue-900",
                      isAssistant && "text-green-900",
                      !isSystem && !isUser && !isAssistant && "text-gray-900",
                    )}
                  >
                    {getRoleLabel(message.role)}
                  </span>
                </div>
                <div
                  className={cn(
                    "comet-code whitespace-pre-wrap break-words rounded-md border px-4 py-3 text-sm leading-relaxed",
                    isSystem && "border-orange-200 bg-white/80 text-orange-950",
                    isUser && "border-blue-200 bg-white/80 text-blue-950",
                    isAssistant &&
                      "border-green-200 bg-white/80 text-green-950",
                    !isSystem &&
                      !isUser &&
                      !isAssistant &&
                      "border-gray-200 bg-white/80 text-gray-950",
                  )}
                >
                  {getMessageContent(message.content)}
                </div>
              </div>
            );
          })}
        </>
      )}
    </div>
  );
};

export default ChatPromptView;
