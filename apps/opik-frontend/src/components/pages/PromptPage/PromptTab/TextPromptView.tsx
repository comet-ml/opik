import React from "react";
import PromptTemplateView from "@/components/pages-shared/llm/PromptTemplateView/PromptTemplateView";

interface TextPromptViewProps {
  template: string;
}

const TextPromptView: React.FC<TextPromptViewProps> = ({ template }) => {
  return <PromptTemplateView template={template} templateStructure="text" />;
};

export default TextPromptView;
