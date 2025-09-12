import React, { useMemo, useState } from "react";
import isFunction from "lodash/isFunction";
import { CircleX, MessageSquareMore } from "lucide-react";

import { TAG_VARIANTS_COLOR_MAP } from "@/components/ui/tag";
import { Button } from "@/components/ui/button";
import { generateTagVariant } from "@/lib/traces";
import { cn } from "@/lib/utils";
import FeedbackScoreReasonTooltip from "./FeedbackScoreReasonTooltip";
import MultiValueFeedbackScoreHoverCard from "./MultiValueFeedbackScoreHoverCard";
import { FeedbackScoreValueByAuthorMap } from "@/types/traces";
import { getIsMultiValueFeedbackScore } from "@/lib/feedback-scores";

type FeedbackScoreTagProps = {
  label: string;
  value: number | string;
  onDelete?: (name: string) => void;
  className?: string;
  lastUpdatedAt?: string;
  lastUpdatedBy?: string;
  reason?: string;
  // Multi-value support
  valueByAuthor?: FeedbackScoreValueByAuthorMap;
  category?: string;
};

const FeedbackScoreTag: React.FunctionComponent<FeedbackScoreTagProps> = ({
  label,
  value,
  reason,
  onDelete,
  className,
  lastUpdatedAt,
  lastUpdatedBy,
  valueByAuthor,
  category,
}) => {
  const [openHoverCard, setOpenHoverCard] = useState(false);

  const color = useMemo(
    () => TAG_VARIANTS_COLOR_MAP[generateTagVariant(label)!],
    [label],
  );

  const isRemovable = isFunction(onDelete);
  const isMultiValue =
    valueByAuthor && getIsMultiValueFeedbackScore(valueByAuthor);

  // Handle reasons for both single and multi-value scores
  const reasons = useMemo(() => {
    if (isMultiValue && valueByAuthor) {
      if (!getIsMultiValueFeedbackScore(valueByAuthor)) {
        return Object.entries(valueByAuthor)
          .map(([author, scoreData]) => {
            const data = scoreData as {
              reason?: string;
              last_updated_at: string;
              value: number;
            };
            return {
              author,
              reason: data.reason || "",
              lastUpdatedAt: data.last_updated_at,
              value: data.value,
            };
          })
          .filter(({ reason }) => reason);
      }

      return [
        {
          reason: String(value),
          author: lastUpdatedBy,
          lastUpdatedAt: lastUpdatedAt,
        },
      ];
    }

    // Single value score
    return reason ? [{ reason, author: lastUpdatedBy, lastUpdatedAt }] : [];
  }, [
    valueByAuthor,
    value,
    reason,
    lastUpdatedBy,
    lastUpdatedAt,
    isMultiValue,
  ]);

  const hasReasons = reasons.length > 0;

  const separatorStyles = hasReasons
    ? "after:absolute after:-left-1 after:h-2 after:w-px after:bg-border pl-px"
    : "";

  const Reason = hasReasons ? (
    <FeedbackScoreReasonTooltip reasons={reasons}>
      <MessageSquareMore className="size-3.5 text-light-slate" />
    </FeedbackScoreReasonTooltip>
  ) : null;

  // Content that will be wrapped in hover card for multi-value or rendered directly for single value
  const tagContent = (
    <div className="flex items-center gap-1.5">
      {/* Icon - rounded div for all feedback scores */}
      <div
        className="rounded-[0.15rem] bg-[var(--bg-color)] p-1"
        style={{ "--bg-color": color } as React.CSSProperties}
      />

      {/* Label */}
      <div
        data-testid="feedback-score-tag-label"
        className="comet-body-s-accented truncate text-muted-slate"
      >
        {label}
      </div>

      {/* Value */}
      <span
        data-testid="feedback-score-tag-value"
        className="comet-body-s-accented"
      >
        {value}
      </span>
    </div>
  );

  return (
    <div
      data-testid="feedback-score-tag"
      className={cn(
        "group flex h-6 items-center gap-1.5 rounded-md border border-border pl-2 pr-2 max-w-full",
        className,
      )}
    >
      <MultiValueFeedbackScoreHoverCard
        color={color}
        valueByAuthor={valueByAuthor!}
        label={label}
        value={value}
        category={category}
        open={openHoverCard}
        onOpenChange={setOpenHoverCard}
      >
        {tagContent}
      </MultiValueFeedbackScoreHoverCard>

      {/* Reason icon */}
      {Reason && Reason}

      {/* Delete button */}
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
          <CircleX />
        </Button>
      )}
    </div>
  );
};

export default FeedbackScoreTag;
