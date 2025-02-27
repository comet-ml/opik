import React, { useMemo } from "react";

import { TAG_VARIANTS_COLOR_MAP } from "@/components/ui/tag";
import { generateTagVariant } from "@/lib/traces";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { cn } from "@/lib/utils";

export interface ColoredTagNewProps {
  label: string;
  className?: string;
  labelClassName?: string;
}

const ColoredTagNew: React.FunctionComponent<ColoredTagNewProps> = ({
  label,
  className,
  labelClassName,
}) => {
  const variant = useMemo(() => generateTagVariant(label), [label]);
  const color = TAG_VARIANTS_COLOR_MAP[generateTagVariant(variant!)!];

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
      <TooltipWrapper content={label}>
        <div
          className={cn(
            "comet-body-s-accented min-w-0 flex-1 truncate text-muted-slate",
            labelClassName,
          )}
        >
          {label}
        </div>
      </TooltipWrapper>
    </div>
  );
};

export default ColoredTagNew;
