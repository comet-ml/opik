import React from "react";
import PromptTemplateView from "@/components/pages-shared/llm/PromptTemplateView/PromptTemplateView";

interface ChatPromptViewProps {
  template: string;
}

const ChatPromptView: React.FC<ChatPromptViewProps> = ({ template }) => {
  return <PromptTemplateView template={template} templateStructure="chat" />;
};

export default ChatPromptView;
