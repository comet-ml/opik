import React, { useMemo, useState } from "react";
import isFunction from "lodash/isFunction";
import isNumber from "lodash/isNumber";
import { CircleX, MessageSquareMore } from "lucide-react";

import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import useWorkspaceColorMap from "@/hooks/useWorkspaceColorMap";
import ColorIndicator from "@/components/shared/ColorIndicator/ColorIndicator";
import FeedbackScoreReasonTooltip from "./FeedbackScoreReasonTooltip";
import MultiValueFeedbackScoreHoverCard from "./MultiValueFeedbackScoreHoverCard";
import { FeedbackScoreValueByAuthorMap } from "@/types/traces";
import {
  extractReasonsFromValueByAuthor,
  getIsMultiValueFeedbackScore,
  categoryOptionLabelRenderer,
  formatScoreDisplay,
} from "@/lib/feedback-scores";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";

type FeedbackScoreTagProps = {
  label: string;
  colorKey?: string;
  value: number | string;
  onDelete?: (name: string) => void;
  className?: string;
  lastUpdatedAt?: string;
  lastUpdatedBy?: string;
  reason?: string;
  // Multi-value support
  valueByAuthor?: FeedbackScoreValueByAuthorMap;
  category?: string;
  color?: string;
};

const FeedbackScoreTag: React.FunctionComponent<FeedbackScoreTagProps> = ({
  label,
  colorKey,
  value,
  reason,
  onDelete,
  className,
  lastUpdatedAt,
  lastUpdatedBy,
  valueByAuthor,
  category,
  color: customColor,
}) => {
  const [openHoverCard, setOpenHoverCard] = useState(false);
  const { getColor } = useWorkspaceColorMap();

  const effectiveColorKey = colorKey ?? label;

  const color = useMemo(
    () => customColor || getColor(effectiveColorKey),
    [customColor, effectiveColorKey, getColor],
  );

  const isRemovable = isFunction(onDelete);

  const reasons = useMemo(() => {
    if (getIsMultiValueFeedbackScore(valueByAuthor)) {
      return extractReasonsFromValueByAuthor(valueByAuthor);
    }

    return reason ? [{ reason, author: lastUpdatedBy, lastUpdatedAt }] : [];
  }, [valueByAuthor, reason, lastUpdatedBy, lastUpdatedAt]);

  const hasReasons = reasons.length > 0;

  const separatorStyles = hasReasons
    ? "after:absolute after:-left-1 after:h-2 after:w-px after:bg-border pl-px"
    : "";

  const Reason = hasReasons ? (
    <FeedbackScoreReasonTooltip reasons={reasons}>
      <MessageSquareMore className="size-3.5 text-light-slate" />
    </FeedbackScoreReasonTooltip>
  ) : null;

  const formattedValue = formatScoreDisplay(value);

  const displayValue = category
    ? categoryOptionLabelRenderer(category, formattedValue)
    : formattedValue;

  const fullPrecisionDisplayValue = isNumber(value)
    ? category
      ? categoryOptionLabelRenderer(category, value)
      : String(value)
    : undefined;

  const isMultiValue = getIsMultiValueFeedbackScore(valueByAuthor);
  const showFullPrecisionTooltip =
    !isMultiValue && fullPrecisionDisplayValue !== undefined;

  // Content that will be wrapped in hover card for multi-value or rendered directly for single value
  const tagContent = (
    <div className="flex max-w-full items-center gap-1.5">
      {/* Icon - rounded div for all feedback scores */}
      <ColorIndicator
        label={label}
        colorKey={effectiveColorKey}
        color={color}
        variant="square"
      />

      {/* Label */}
      <div
        data-testid="feedback-score-tag-label"
        className="comet-body-s-accented min-w-0 truncate text-muted-slate"
      >
        {label}
      </div>

      {/* Value */}
      <TooltipWrapper
        content={
          showFullPrecisionTooltip ? fullPrecisionDisplayValue : undefined
        }
      >
        <span
          data-testid="feedback-score-tag-value"
          className="comet-body-s-accented shrink-0"
        >
          {displayValue}
        </span>
      </TooltipWrapper>
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
