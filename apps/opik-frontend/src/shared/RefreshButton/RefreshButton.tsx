import React, { useState } from "react";
import { RotateCw } from "lucide-react";
import { Button, ButtonProps } from "@/ui/button";
import { cn } from "@/lib/utils";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";

interface RefreshButtonProps {
  onRefresh: () => void;
  isFetching?: boolean;
  tooltip: string;
  label?: string;
  variant?: ButtonProps["variant"];
  size?: ButtonProps["size"];
  className?: string;
}

const RefreshButton: React.FC<RefreshButtonProps> = ({
  onRefresh,
  isFetching = false,
  tooltip,
  label,
  variant = "outline",
  size = "icon-sm",
  className,
}) => {
  const [spin, setSpin] = useState(false);
  const isSpinning = spin || isFetching;

  return (
    <TooltipWrapper content={tooltip}>
      <Button
        variant={variant}
        size={size}
        className={cn("shrink-0", className)}
        onClick={() => {
          setSpin(true);
          onRefresh();
        }}
        disabled={isSpinning}
      >
        <RotateCw
          className={cn(
            label && "mr-1.5 size-3.5",
            isSpinning && "animate-spin",
          )}
          onAnimationIteration={() => {
            if (!isFetching) setSpin(false);
          }}
        />
        {label}
      </Button>
    </TooltipWrapper>
  );
};

export default RefreshButton;
