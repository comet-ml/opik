import React, { useMemo } from "react";
import { MessageCircle, User, Bot } from "lucide-react";
import { cn } from "@/lib/utils";

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
  const messages = useMemo<ChatMessage[]>(() => {
    try {
      const parsed = JSON.parse(template);
      return Array.isArray(parsed) ? parsed : [];
    } catch {
      return [];
    }
  }, [template]);

  const getMessageContent = (content: string | Array<{ type: string; [key: string]: unknown }>): string => {
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

  const getRoleColor = (role: string) => {
    switch (role.toLowerCase()) {
      case "system":
        return "bg-orange-100 text-orange-800 border-orange-200";
      case "user":
        return "bg-blue-100 text-blue-800 border-blue-200";
      case "assistant":
        return "bg-green-100 text-green-800 border-green-200";
      default:
        return "bg-gray-100 text-gray-800 border-gray-200";
    }
  };

  if (messages.length === 0) {
    return (
      <div className="flex items-center justify-center p-8 text-muted-foreground">
        <p>No messages found in chat prompt</p>
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-4">
      {messages.map((message, index) => (
        <div
          key={index}
          className="flex flex-col gap-2 rounded-md border bg-background p-4"
        >
          <div className="flex items-center gap-2">
            <div
              className={cn(
                "flex items-center gap-1.5 rounded px-2 py-1 text-xs font-medium border",
                getRoleColor(message.role)
              )}
            >
              {getRoleIcon(message.role)}
              <span>{getRoleLabel(message.role)}</span>
            </div>
          </div>
          <code className="comet-code flex w-full whitespace-pre-wrap break-all rounded-md bg-primary-foreground p-3">
            {getMessageContent(message.content)}
          </code>
        </div>
      ))}
    </div>
  );
};

export default ChatPromptView;

