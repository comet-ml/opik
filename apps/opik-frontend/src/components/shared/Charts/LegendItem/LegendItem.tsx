import React from "react";
import { cn } from "@/lib/utils";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import ColorIndicator from "@/components/shared/ColorIndicator/ColorIndicator";

export type LegendLabelAction = {
  onClick?: () => void;
  tooltip?: string;
};

type LegendItemProps = {
  itemValue: string;
  displayLabel: string;
  indicatorColor: string;
  action?: LegendLabelAction;
  onMouseEnter: () => void;
  className?: string;
  dotClassName?: string;
};

const LegendItem: React.FC<LegendItemProps> = ({
  itemValue,
  displayLabel,
  indicatorColor,
  action,
  onMouseEnter,
  className,
  dotClassName,
}) => {
  const isClickable = !!action?.onClick;
  const tooltipContent = action?.tooltip ?? displayLabel;

  return (
    <div
      className={cn(
        "relative duration-200 group-hover-except-self:opacity-60 min-h-4",
        isClickable && "cursor-pointer",
        className,
      )}
      onMouseEnter={onMouseEnter}
      onClick={action?.onClick}
    >
      <TooltipWrapper content={tooltipContent}>
        <div
          className={cn(
            "comet-body-xs truncate text-foreground",
            isClickable && "border-b border-foreground hover:text-primary",
          )}
        >
          {displayLabel}
        </div>
      </TooltipWrapper>
      <ColorIndicator
        label={itemValue}
        color={indicatorColor}
        variant="dot"
        className={dotClassName}
      />
    </div>
  );
};

export default LegendItem;
