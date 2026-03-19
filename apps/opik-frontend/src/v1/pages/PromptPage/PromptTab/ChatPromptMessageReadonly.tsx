import React from "react";
import { PromptMessageCard } from "@/v1/pages-shared/llm/PromptMessagesReadonly/PromptMessagesReadonly";

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
  return <PromptMessageCard message={message} />;
};

export default ChatPromptMessageReadonly;
