import React from "react";
import { cn } from "@/lib/utils";
import { PrettyLLMMessageContentProps } from "./types";
import { AccordionContent } from "@/components/ui/accordion";

const PrettyLLMMessageContent: React.FC<PrettyLLMMessageContentProps> = ({
  children,
  className,
}) => {
  return (
    <AccordionContent
      className={cn(
        "ml-[10px] pb-0.5 border-l pl-[12px] space-y-1 py-1",
        className,
      )}
    >
      {children}
    </AccordionContent>
  );
};

export default PrettyLLMMessageContent;
