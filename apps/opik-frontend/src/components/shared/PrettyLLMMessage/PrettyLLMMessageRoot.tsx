import React from "react";
import { cn } from "@/lib/utils";
import { PrettyLLMMessageRootProps } from "./types";
import { CollapsibleSectionRoot } from "@/components/shared/CollapsibleSection";

const PrettyLLMMessageRoot: React.FC<PrettyLLMMessageRootProps> = ({
  value,
  children,
  className,
}) => {
  return (
    <CollapsibleSectionRoot value={value} className={cn(className)}>
      {children}
    </CollapsibleSectionRoot>
  );
};

export default PrettyLLMMessageRoot;
