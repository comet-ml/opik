import React from "react";
import { cn } from "@/lib/utils";
import { PrettyLLMMessageRootProps } from "./types";
import { AccordionItem } from "@/components/ui/accordion";

const PrettyLLMMessageRoot: React.FC<PrettyLLMMessageRootProps> = ({
  value,
  children,
  className,
}) => {
  return (
    <AccordionItem value={value} className={cn("border-none", className)}>
      {children}
    </AccordionItem>
  );
};

export default PrettyLLMMessageRoot;
