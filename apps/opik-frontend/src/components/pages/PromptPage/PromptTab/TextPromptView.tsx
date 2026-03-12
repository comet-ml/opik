import React from "react";
import PromptTemplateView from "@/components/pages-shared/llm/PromptTemplateView/PromptTemplateView";
import { PROMPT_TEMPLATE_STRUCTURE } from "@/types/prompts";

interface TextPromptViewProps {
  template: string;
}

const TextPromptView: React.FC<TextPromptViewProps> = ({ template }) => {
  return (
    <PromptTemplateView
      template={template}
      templateStructure={PROMPT_TEMPLATE_STRUCTURE.TEXT}
    />
  );
};

export default TextPromptView;
