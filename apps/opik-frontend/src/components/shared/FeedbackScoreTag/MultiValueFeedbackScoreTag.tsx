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
import MultiValueFeedbackScoreName from "./MultiValueFeedbackScoreName";

type MultiValueFeedbackScoreTagProps = {
  label: string;
  value: number | string;
  valueByAuthor: FeedbackScoreValueByAuthorMap;
  category?: string;
  onDelete?: (name: string) => void;
  className?: string;
};

const MultiValueFeedbackScoreTag: React.FunctionComponent<
  MultiValueFeedbackScoreTagProps
> = ({ label, value, valueByAuthor, category, onDelete, className }) => {
  const [openHoverCard, setOpenHoverCard] = useState(false);
  const color = useMemo(
    () => TAG_VARIANTS_COLOR_MAP[generateTagVariant(label)!],
    [label],
  );

  const isRemovable = isFunction(onDelete);

  const reasons = useMemo(() => {
    return Object.entries(valueByAuthor)
      .map(([author, { reason, last_updated_at, value }]) => ({
        author,
        reason: reason || "",
        lastUpdatedAt: last_updated_at,
        value,
      }))
      .filter(({ reason }) => reason);
  }, [valueByAuthor]);
  const hasReasons = reasons.length > 0;

  const separatorStyles = hasReasons
    ? "after:absolute after:-left-1 after:h-2 after:w-px after:bg-[#E2E8F0] pl-px"
    : "";

  const Reason = hasReasons ? (
    <FeedbackScoreReasonTooltip reasons={reasons}>
      <MessageSquareMore className="size-3.5 text-light-slate" />
    </FeedbackScoreReasonTooltip>
  ) : null;

  return (
    <div
      data-testid="feedback-score-tag"
      className={cn(
        "group flex h-6 items-center gap-1 rounded-md border border-border pl-1 pr-2 max-w-full",
        className,
      )}
    >
      <MultiValueFeedbackScoreHoverCard
        color={color}
        valueByAuthor={valueByAuthor}
        label={label}
        value={value}
        category={category}
        open={openHoverCard}
        onOpenChange={setOpenHoverCard}
      >
        <div className="flex items-center gap-1.5">
          <MultiValueFeedbackScoreName label={label} />

          <span
            data-testid="feedback-score-tag-value"
            className="comet-body-s-accented"
          >
            {value}
          </span>
        </div>
      </MultiValueFeedbackScoreHoverCard>

      {Reason && Reason}

      {isRemovable && (
        <Button
          size="icon-xs"
          variant="minimal"
          className={cn(
            "relative hidden w-auto shrink-0 group-hover:flex ml-0.5",
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

export default MultiValueFeedbackScoreTag;
