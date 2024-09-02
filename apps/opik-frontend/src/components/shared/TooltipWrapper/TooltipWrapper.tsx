import React from "react";
import {
  Tooltip,
  TooltipArrow,
  TooltipContent,
  TooltipPortal,
  TooltipProvider,
  TooltipTrigger,
} from "@/components/ui/tooltip";

type TooltipWrapperProps = {
  content: string;
  children?: React.ReactNode;
  side?: "top" | "right" | "bottom" | "left";
};

const TooltipWrapper: React.FunctionComponent<TooltipWrapperProps> = ({
  content,
  children,
  side,
}) => {
  return (
    <TooltipProvider>
      <Tooltip>
        <TooltipTrigger asChild>{children}</TooltipTrigger>
        <TooltipPortal>
          <TooltipContent side={side}>
            {content}
            <TooltipArrow />
          </TooltipContent>
        </TooltipPortal>
      </Tooltip>
    </TooltipProvider>
  );
};

export default TooltipWrapper;
