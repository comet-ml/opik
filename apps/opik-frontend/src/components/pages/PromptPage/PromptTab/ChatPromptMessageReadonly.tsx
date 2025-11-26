import React, { useMemo } from "react";
import { LLM_MESSAGE_ROLE_NAME_MAP } from "@/constants/llm";
import { LLM_MESSAGE_ROLE } from "@/types/llm";
import CodeHighlighter, {
  SUPPORTED_LANGUAGE,
} from "@/components/shared/CodeHighlighter/CodeHighlighter";

interface ChatMessage {
  role: string;
  content: string | Array<{ type: string; [key: string]: unknown }>;
}

interface ChatPromptMessageReadonlyProps {
  message: ChatMessage;
}

const ChatPromptMessageReadonly: React.FC<ChatPromptMessageReadonlyProps> = ({
  message,
}) => {
  const getRoleLabel = (role: string): string => {
    const roleKey = role.toUpperCase() as keyof typeof LLM_MESSAGE_ROLE;
    if (LLM_MESSAGE_ROLE[roleKey]) {
      return LLM_MESSAGE_ROLE_NAME_MAP[LLM_MESSAGE_ROLE[roleKey]] || role;
    }
    return role.charAt(0).toUpperCase() + role.slice(1);
  };

  const getTextAndAttachments = (
    content: string | Array<{ type: string; [key: string]: unknown }>,
  ): {
    text: string;
    attachments: Array<{ type: string; [key: string]: unknown }>;
  } => {
    if (typeof content === "string") {
      return { text: content, attachments: [] };
    }

    // Handle multimodal content
    if (Array.isArray(content)) {
      const textParts = content
        .filter((part) => part.type === "text")
        .map((part) => part.text || "")
        .join("\n");

      const attachments = content.filter((part) => part.type !== "text");

      return { text: textParts, attachments };
    }

    return { text: "", attachments: [] };
  };

  const { text: displayText, attachments } = getTextAndAttachments(
    message.content,
  );

  // Memoize the stringified attachments to avoid costly JSON.stringify on every render
  const stringifiedAttachments = useMemo(
    () =>
      attachments.map((attachment) => JSON.stringify(attachment, null, 2)),
    [attachments],
  );

  return (
    <div className="flex flex-col gap-2.5 rounded-md border bg-primary-foreground p-3">
      <div className="flex items-center">
        <span className="comet-body-s-accented text-light-slate">
          {getRoleLabel(message.role)}
        </span>
      </div>
      {displayText && (
        <div className="comet-body-s whitespace-pre-wrap break-words text-foreground">
          {displayText}
        </div>
      )}
      {attachments.length > 0 && (
        <div className="space-y-2">
          {stringifiedAttachments.map((stringifiedAttachment, index) => (
            <div key={index} className="rounded border">
              <CodeHighlighter
                data={stringifiedAttachment}
                language={SUPPORTED_LANGUAGE.json}
              />
            </div>
          ))}
        </div>
      )}
    </div>
  );
};

export default ChatPromptMessageReadonly;
