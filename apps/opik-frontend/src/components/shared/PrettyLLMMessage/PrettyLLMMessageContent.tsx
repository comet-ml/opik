import React from "react";
import * as AccordionPrimitive from "@radix-ui/react-accordion";
import { cn } from "@/lib/utils";
import { PrettyLLMMessageContentProps } from "./types";

const PrettyLLMMessageContent: React.FC<PrettyLLMMessageContentProps> = ({
  children,
  className,
}) => {
  return (
    <AccordionPrimitive.Content
      className={cn(
        "ml-[6px] pt-1 pb-2 px-0 border-l pl-[12px] space-y-3",
        className,
      )}
    >
      {children}
    </AccordionPrimitive.Content>
  );
};

export default PrettyLLMMessageContent;
