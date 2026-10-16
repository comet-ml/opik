import React from "react";

import { cn } from "@/lib/utils";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";

// Radix tooltips don't fire on elements with pointer-events: none (which
// disabled buttons get from the Button variants), so when `disabled` is true
// we wrap the child in a span that intercepts hover/focus for the tooltip.
type DisabledTooltipProps = {
  message: string;
  disabled: boolean;
  children: React.ReactNode;
  className?: string;
};

const DisabledTooltip: React.FC<DisabledTooltipProps> = ({
  message,
  disabled,
  children,
  className,
}) => (
  <TooltipWrapper content={disabled ? message : null}>
    <span
      className={cn("inline-flex", disabled && "cursor-not-allowed", className)}
    >
      {children}
    </span>
  </TooltipWrapper>
);

export default DisabledTooltip;
