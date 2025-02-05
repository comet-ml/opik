import React, { useMemo } from "react";
import isFunction from "lodash/isFunction";
import { CircleX, MessageSquareMore } from "lucide-react";

import { TAG_VARIANTS_COLOR_MAP } from "@/components/ui/tag";
import { Button } from "@/components/ui/button";
import { generateTagVariant } from "@/lib/traces";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { cn } from "@/lib/utils";

type FeedbackScoreTagProps = {
  label: string;
  value: number;
  reason?: string;
  onDelete?: (name: string) => void;
  className?: string;
};

const FeedbackScoreTag: React.FunctionComponent<FeedbackScoreTagProps> = ({
  label,
  value,
  reason,
  onDelete,
  className,
}) => {
  const variant = useMemo(() => generateTagVariant(label), [label]);
  const color = TAG_VARIANTS_COLOR_MAP[generateTagVariant(variant!)!];

  const isRemovable = isFunction(onDelete);

  const separatorStyles = reason
    ? "after:absolute after:-left-1 after:h-2 after:w-px after:bg-[#E2E8F0] pl-px"
    : "";

  const Reason = reason ? (
    <TooltipWrapper content={reason} delayDuration={100}>
      <MessageSquareMore className="size-3.5 text-light-slate" />
    </TooltipWrapper>
  ) : null;

  return (
    <div
      data-testid="feedback-score-tag"
      className={cn(
        "group flex h-6 items-center gap-2 rounded-md border border-border pl-2 pr-2",
        className,
      )}
    >
      <div
        className="rounded-[0.15rem] bg-[var(--bg-color)] p-1"
        style={{ "--bg-color": color } as React.CSSProperties}
      />
      <div
        data-testid="feedback-score-tag-label"
        className="comet-body-s-accented truncate leading-none text-light-slate"
      >
        {label}
      </div>
      <div className="flex items-center gap-1">
        <span
          data-testid="feedback-score-tag-value"
          className="comet-body-s-accented"
        >
          {value}
        </span>
        {Reason && Reason}
      </div>
      {isRemovable && (
        <Button
          size="icon-xs"
          variant="minimal"
          className={cn(
            "relative hidden w-auto shrink-0 group-hover:flex",
            separatorStyles,
          )}
          onClick={() => onDelete(label)}
        >
          <CircleX className="size-3.5" />
        </Button>
      )}
    </div>
  );
};

export default FeedbackScoreTag;
