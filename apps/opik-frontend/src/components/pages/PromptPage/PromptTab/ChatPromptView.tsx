import React from "react";
import PromptTemplateView from "@/components/pages-shared/llm/PromptTemplateView/PromptTemplateView";
import { PROMPT_TEMPLATE_STRUCTURE } from "@/types/prompts";

interface ChatPromptViewProps {
  template: string;
}

const ChatPromptView: React.FC<ChatPromptViewProps> = ({ template }) => {
  return (
    <PromptTemplateView
      template={template}
      templateStructure={PROMPT_TEMPLATE_STRUCTURE.CHAT}
    />
  );
};

export default ChatPromptView;
