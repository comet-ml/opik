import React, { useCallback, useRef, useState } from "react";
import { TooltipProps } from "@radix-ui/react-tooltip";

import {
  Tooltip,
  TooltipArrow,
  TooltipContent,
  TooltipPortal,
  TooltipTrigger,
} from "@/ui/tooltip";
import { TOOLTIP_DELAY_DURATION } from "@/constants/shared";

export type TooltipWrapperProps = {
  content?: string | React.ReactElement | null;
  children?: React.ReactNode;
  side?: "top" | "right" | "bottom" | "left";
  hotkeys?: React.ReactNode[];
  delayDuration?: number;
  defaultOpen?: TooltipProps["defaultOpen"];
  stopClickPropagation?: boolean;
  // Open on hover only, never on focus. Use when the trigger receives focus
  // programmatically (e.g. a Select/Popover restoring focus to it on close),
  // which would otherwise pop the tooltip open unexpectedly.
  hoverOnly?: boolean;
};

const TooltipWrapper: React.FunctionComponent<TooltipWrapperProps> = ({
  content,
  children,
  side,
  hotkeys = null,
  delayDuration,
  defaultOpen,
  stopClickPropagation,
  hoverOnly = false,
}) => {
  const [open, setOpen] = useState(false);
  const timerRef = useRef<number | null>(null);

  const handlePointerEnter = useCallback(() => {
    if (timerRef.current) window.clearTimeout(timerRef.current);
    timerRef.current = window.setTimeout(
      () => setOpen(true),
      delayDuration ?? TOOLTIP_DELAY_DURATION,
    );
  }, [delayDuration]);

  const handlePointerLeave = useCallback(() => {
    if (timerRef.current) window.clearTimeout(timerRef.current);
    setOpen(false);
  }, []);

  // Honor Radix dismissals (Escape / pointer-outside) but ignore its open
  // requests, so focus can't open the tooltip while hover still can.
  const handleOpenChange = useCallback((next: boolean) => {
    if (!next) {
      if (timerRef.current) window.clearTimeout(timerRef.current);
      setOpen(false);
    }
  }, []);

  if (!content) {
    return <>{children}</>;
  }

  return (
    <Tooltip
      {...(hoverOnly
        ? { open, onOpenChange: handleOpenChange }
        : { defaultOpen, delayDuration })}
    >
      <TooltipTrigger
        asChild
        {...(hoverOnly && {
          onPointerEnter: handlePointerEnter,
          onPointerLeave: handlePointerLeave,
        })}
      >
        {children}
      </TooltipTrigger>
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
