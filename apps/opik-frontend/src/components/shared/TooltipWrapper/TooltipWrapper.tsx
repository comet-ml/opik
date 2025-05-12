import React from "react";
import { TooltipProps } from "@radix-ui/react-tooltip";

import {
  Tooltip,
  TooltipArrow,
  TooltipContent,
  TooltipPortal,
  TooltipTrigger,
} from "@/components/ui/tooltip";

export type TooltipWrapperProps = {
  content: string | React.ReactElement;
  children?: React.ReactNode;
  side?: "top" | "right" | "bottom" | "left";
  hotkeys?: React.ReactNode[];
  delayDuration?: number;
  defaultOpen?: TooltipProps["defaultOpen"];
  stopClickPropagation?: boolean;
};

const TooltipWrapper: React.FunctionComponent<TooltipWrapperProps> = ({
  content,
  children,
  side,
  hotkeys = null,
  delayDuration,
  defaultOpen,
  stopClickPropagation,
}) => {
  return (
    <Tooltip defaultOpen={defaultOpen} delayDuration={delayDuration}>
      <TooltipTrigger asChild>{children}</TooltipTrigger>
      <TooltipPortal>
        <TooltipContent
          side={side}
          variant={hotkeys?.length ? "hotkey" : "default"}
          collisionPadding={16}
          {...(stopClickPropagation && {
            onClick: (event) => event.stopPropagation(),
          })}
        >
          {content}

          {hotkeys && (
            <div className="ml-2 flex gap-1">
              {hotkeys.map((hotkey, idx) => (
                <React.Fragment key={idx}>
                  <div className="flex h-5 min-w-5 items-center justify-center rounded-sm border border-light-slate px-1 text-light-slate">
                    {hotkey}
                  </div>
                  {idx < hotkeys.length - 1 && (
                    <div className="text-light-slate">+</div>
                  )}
                </React.Fragment>
              ))}
            </div>
          )}
          {hotkeys?.length && <TooltipArrow />}
        </TooltipContent>
      </TooltipPortal>
    </Tooltip>
  );
};

export default TooltipWrapper;
