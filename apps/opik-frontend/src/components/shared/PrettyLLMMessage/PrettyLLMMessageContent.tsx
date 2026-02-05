import React from "react";
import { cn } from "@/lib/utils";
import { PrettyLLMMessageContentProps } from "./types";
import { CollapsibleSectionContent } from "@/components/shared/CollapsibleSection";

const PrettyLLMMessageContent: React.FC<PrettyLLMMessageContentProps> = ({
  children,
  className,
}) => {
  return (
    <CollapsibleSectionContent className={cn(className)}>
      {children}
    </CollapsibleSectionContent>
  );
};

export default PrettyLLMMessageContent;
