import React, { useMemo } from "react";
import DoubleSquare from "@/icons/double-square.svg?react";
import { TAG_VARIANTS_COLOR_MAP } from "@/components/ui/tag";
import { generateTagVariant } from "@/lib/traces";
import { cn } from "@/lib/utils";

type MultiValueFeedbackScoreNameProps = {
  label: string;
  className?: string;
};

const MultiValueFeedbackScoreName: React.FC<
  MultiValueFeedbackScoreNameProps
> = ({ label, className }) => {
  const color = useMemo(
    () => TAG_VARIANTS_COLOR_MAP[generateTagVariant(label)!],
    [label],
  );

  return (
    <div className={cn("flex items-center gap-0.5", className)}>
      <DoubleSquare
        className="size-4 text-[var(--bg-color)]"
        style={{ "--bg-color": color } as React.CSSProperties}
      />
      <div className="comet-body-s-accented truncate text-muted-slate">
        {label}
      </div>
    </div>
  );
};

export default MultiValueFeedbackScoreName;
