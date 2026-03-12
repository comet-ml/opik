import React, { useMemo } from "react";
import { cva } from "class-variance-authority";

import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import ColorIndicator from "@/components/shared/ColorIndicator/ColorIndicator";
import useWorkspaceColorMap from "@/hooks/useWorkspaceColorMap";
import { cn } from "@/lib/utils";

export interface ColoredTagNewProps {
  label: string;
  colorKey?: string;
  className?: string;
  size?: "sm" | "default";
  readOnly?: boolean;
}

const labelVariants = cva("min-w-0 flex-1 truncate text-muted-slate", {
  variants: {
    size: {
      sm: "comet-body-xs",
      default: "comet-body-s-accented",
    },
  },
  defaultVariants: {
    size: "default",
  },
});

const ColoredTagNew: React.FunctionComponent<ColoredTagNewProps> = ({
  label,
  colorKey,
  className,
  size = "default",
  readOnly = false,
}) => {
  const { getColor } = useWorkspaceColorMap();
  const effectiveColorKey = colorKey ?? label;
  const color = useMemo(
    () => getColor(effectiveColorKey),
    [effectiveColorKey, getColor],
  );

  return (
    <div
      data-testid="feedback-score-tag"
      className={cn(
        "flex items-center gap-1.5 rounded-md border border-transparent px-2 max-w-full",
        className,
      )}
    >
      <ColorIndicator
        label={label}
        colorKey={effectiveColorKey}
        color={color}
        variant="square"
        className="grow-0"
        readOnly={readOnly}
      />
      <TooltipWrapper content={label} stopClickPropagation>
        <div className={cn(labelVariants({ size }))}>{label}</div>
      </TooltipWrapper>
    </div>
  );
};

export default ColoredTagNew;
