import React from "react";

interface PromptModelConfigsTooltipContentProps {
  text: string;
}

const PromptModelConfigsTooltipContent = ({
  text,
}: PromptModelConfigsTooltipContentProps) => {
  return <p className="max-w-72 p-1">{text}</p>;
};

export default PromptModelConfigsTooltipContent;
