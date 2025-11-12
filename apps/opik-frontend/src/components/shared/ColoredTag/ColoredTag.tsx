import React, { useMemo } from "react";
import { cva } from "class-variance-authority";

import { TAG_VARIANTS_COLOR_MAP } from "@/components/ui/tag";
import { generateTagVariant } from "@/lib/traces";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { cn } from "@/lib/utils";

export interface ColoredTagProps {
  label: string;
  size?: "sm" | "default" | "md";
  testId?: string;
  className?: string;
}

const labelVariants = cva("min-w-0 flex-1 truncate text-muted-slate", {
  variants: {
    size: {
      sm: "comet-body-xs",
      default: "comet-body-s-accented",
      md: "comet-body-s-accented",
    },
  },
  defaultVariants: {
    size: "default",
  },
});

const containerVariants = cva(
  "flex max-w-full items-center gap-1.5 rounded-md border border-border",
  {
    variants: {
      size: {
        sm: "h-5 px-1.5",
        default: "h-6 px-2",
        md: "h-6 px-2",
      },
    },
    defaultVariants: {
      size: "default",
    },
  },
);

const ColoredTag: React.FunctionComponent<ColoredTagProps> = ({
  label,
  size = "md",
  testId,
  className,
}) => {
  const color = useMemo(
    () => TAG_VARIANTS_COLOR_MAP[generateTagVariant(label)!],
    [label],
  );

  return (
    <div
      data-testid={testId}
      className={cn(containerVariants({ size }), className)}
    >
      <div
        className="size-2 shrink-0 rounded-[0.15rem]"
        style={{ backgroundColor: color }}
      />
      <TooltipWrapper content={label} stopClickPropagation>
        <div className={cn(labelVariants({ size }))}>{label}</div>
      </TooltipWrapper>
    </div>
  );
};

export default ColoredTag;
