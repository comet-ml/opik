import React, { useMemo } from "react";
import { cva } from "class-variance-authority";

import { TAG_VARIANTS_COLOR_MAP } from "@/components/ui/tag";
import { generateTagVariant } from "@/lib/traces";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { cn } from "@/lib/utils";

export interface ColoredTagNewProps {
  label: string;
  className?: string;
  size?: "sm" | "default";
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
  className,
  size = "default",
}) => {
  const color = useMemo(
    () => TAG_VARIANTS_COLOR_MAP[generateTagVariant(label)!],
    [label],
  );

  return (
    <div
      data-testid="feedback-score-tag"
      className={cn(
        "flex items-center gap-1.5 rounded-md border border-transparent px-2 max-w-full",
        className,
      )}
    >
      <div
        className="grow-0 rounded-[2px] bg-[var(--bg-color)] p-1"
        style={{ "--bg-color": color } as React.CSSProperties}
      />
      <TooltipWrapper content={label} stopClickPropagation>
        <div className={cn(labelVariants({ size }))}>{label}</div>
      </TooltipWrapper>
    </div>
  );
};

export default ColoredTagNew;
