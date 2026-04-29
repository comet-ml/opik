import React, { useEffect, useRef, useState } from "react";
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

// Fallback in case `animationiteration` never fires (e.g. animations
// suppressed) so the button can't stay disabled forever.
const SPIN_FALLBACK_MS = 1200;

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
  const fallbackRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const isSpinning = spin || isFetching;

  useEffect(
    () => () => {
      if (fallbackRef.current) clearTimeout(fallbackRef.current);
    },
    [],
  );

  const handleClick = () => {
    setSpin(true);
    if (fallbackRef.current) clearTimeout(fallbackRef.current);
    fallbackRef.current = setTimeout(() => setSpin(false), SPIN_FALLBACK_MS);
    onRefresh();
  };

  return (
    <TooltipWrapper content={tooltip}>
      <Button
        variant={variant}
        size={size}
        className={cn("shrink-0", className)}
        onClick={handleClick}
        disabled={isSpinning}
      >
        <span
          className={cn(
            "inline-flex shrink-0",
            label && "mr-1.5",
            isSpinning && "animate-spin",
          )}
          onAnimationIteration={() => {
            if (!isFetching) setSpin(false);
          }}
        >
          <RotateCw className="size-3.5" />
        </span>
        {label}
      </Button>
    </TooltipWrapper>
  );
};

export default RefreshButton;
