import React from "react";
import { cn } from "@/lib/utils";
import { PrettyLLMMessageContainerProps } from "./types";
import { CollapsibleSectionContainer } from "@/components/shared/CollapsibleSection";

const PrettyLLMMessageContainer: React.FC<PrettyLLMMessageContainerProps> = ({
  children,
  className,
  ...props
}) => {
  return (
    <CollapsibleSectionContainer
      className={cn("group/llm-message", className)}
      {...props}
    >
      {children}
    </CollapsibleSectionContainer>
  );
};

export default PrettyLLMMessageContainer;
