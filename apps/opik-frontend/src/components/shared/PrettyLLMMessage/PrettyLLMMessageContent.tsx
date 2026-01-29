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
        "ml-[6px] pt-1 pb-2 px-0 border-l pl-[12px] space-y-3",
        className,
      )}
    >
      {children}
    </AccordionContent>
  );
};

export default PrettyLLMMessageContent;
