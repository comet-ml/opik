import React from "react";
import { cn } from "@/lib/utils";
import { PrettyLLMMessageContainerProps } from "./types";
import { Accordion } from "@/components/ui/accordion";

const PrettyLLMMessageContainer: React.FC<PrettyLLMMessageContainerProps> = ({
  children,
  className,
  ...props
}) => {
  return (
    <Accordion className={cn("group/llm-message", className)} {...props}>
      {children}
    </Accordion>
  );
};

export default PrettyLLMMessageContainer;
