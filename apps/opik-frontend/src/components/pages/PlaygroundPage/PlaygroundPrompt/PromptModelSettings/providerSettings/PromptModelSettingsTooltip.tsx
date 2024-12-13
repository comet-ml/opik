import React from "react";

interface PromptModelSettingsTooltipContentProps {
  text: string;
}

const PromptModelSettingsTooltipContent = ({
  text,
}: PromptModelSettingsTooltipContentProps) => {
  return <p className="max-w-72 p-1">{text}</p>;
};

export default PromptModelSettingsTooltipContent;
