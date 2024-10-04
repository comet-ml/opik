import React, { useMemo } from "react";
import isFunction from "lodash/isFunction";
import { CircleX, MessageSquareMore } from "lucide-react";

import { Tag } from "@/components/ui/tag";
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
  const isRemovable = isFunction(onDelete);

  const Reason = reason ? (
    <TooltipWrapper content={reason} delayDuration={100}>
      <MessageSquareMore className="size-3.5 text-light-slate" />
    </TooltipWrapper>
  ) : null;

  return (
    <div
      data-testid="feedback-score-tag"
      className={cn(
        "group flex h-7 items-center gap-2 rounded-md border border-border pl-1 pr-3",
        className,
      )}
    >
      <Tag data-testid="feedback-score-tag-label" variant={variant}>
        {label}
      </Tag>
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
          className="-ml-0.5 -mr-2 hidden shrink-0 group-hover:flex"
          onClick={() => onDelete(label)}
        >
          <CircleX className="size-3.5" />
        </Button>
      )}
    </div>
  );
};

export default FeedbackScoreTag;
