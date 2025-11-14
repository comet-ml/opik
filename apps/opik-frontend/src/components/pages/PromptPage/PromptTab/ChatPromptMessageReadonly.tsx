import React from "react";
import { LLM_MESSAGE_ROLE_NAME_MAP } from "@/constants/llm";
import { LLM_MESSAGE_ROLE } from "@/types/llm";
import PromptMessageImageTags from "@/components/pages-shared/llm/PromptMessageImageTags/PromptMessageImageTags";
import { parseContentWithImages } from "@/lib/llm";

interface ChatMessage {
  role: string;
  content: string | Array<{ type: string; [key: string]: unknown }>;
}

interface ChatPromptMessageReadonlyProps {
  message: ChatMessage;
}

const ChatPromptMessageReadonly: React.FunctionComponent<
  ChatPromptMessageReadonlyProps
> = ({ message }) => {
  const getRoleLabel = (role: string): string => {
    const roleKey = role.toUpperCase() as keyof typeof LLM_MESSAGE_ROLE;
    if (LLM_MESSAGE_ROLE[roleKey]) {
      return LLM_MESSAGE_ROLE_NAME_MAP[LLM_MESSAGE_ROLE[roleKey]] || role;
    }
    return role.charAt(0).toUpperCase() + role.slice(1);
  };

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

  const messageText = getMessageContent(message.content);
  const { text: displayText, images: extractedImages } =
    parseContentWithImages(messageText);

  return (
    <div className="flex flex-col gap-2.5 rounded-md border bg-primary-foreground p-3">
      <div className="flex items-center">
        <span className="comet-body-s-accented text-light-slate">
          {getRoleLabel(message.role)}
        </span>
      </div>
      <div className="comet-body-s whitespace-pre-wrap break-words text-foreground">
        {displayText}
      </div>
      {extractedImages.length > 0 && (
        <PromptMessageImageTags
          images={extractedImages}
          setImages={() => {}}
          editable={false}
          preview={true}
          align="start"
        />
      )}
    </div>
  );
};

export default ChatPromptMessageReadonly;
