import MultiValueFeedbackScoreHoverCard from "../FeedbackScoreTag/MultiValueFeedbackScoreHoverCard";
import {
  FeedbackScoreValueByAuthorMap,
  TraceFeedbackScore,
} from "@/types/traces";
import { useState } from "react";
import FeedbackScoreEditDropdown from "./FeedbackScoreEditDropdown";
import {
  formatScoreDisplay,
  getIsMultiValueFeedbackScore,
} from "@/lib/feedback-scores";
import {
  getCategoricFeedbackScoreValuesMap,
  getIsCategoricFeedbackScore,
} from "@/shared/FeedbackScoreTag/utils";
import { isAggregatedScore, getTrialAvgTooltip } from "@/lib/trials";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import useWorkspaceColorMap from "@/hooks/useWorkspaceColorMap";

const MAX_DISPLAY_CATEGORIES = 2;

const formatMultiValueDisplay = (
  valueByAuthor: FeedbackScoreValueByAuthorMap,
  category: string | undefined,
  avgValue: number | string,
): string => {
  if (getIsCategoricFeedbackScore(category)) {
    const scoreMap = getCategoricFeedbackScoreValuesMap(valueByAuthor);
    const entries = Array.from(scoreMap.values());
    const displayed = entries
      .slice(0, MAX_DISPLAY_CATEGORIES)
      .map(({ users, value }) => `${users.length}x ${value}`)
      .join(", ");
    const remaining = entries.length - MAX_DISPLAY_CATEGORIES;
    return remaining > 0 ? `${displayed}, +${remaining}` : displayed;
  }
  return `avg ${formatScoreDisplay(avgValue)}`;
};

const FeedbackScoreCellValue = ({
  isUserFeedbackColumn = false,
  feedbackScore,
  color: customColor,
  onValueChange,
  showTooltip = false,
  size = "md",
}: {
  isUserFeedbackColumn?: boolean;
  feedbackScore?: TraceFeedbackScore;
  color?: string;
  onValueChange?: (name: string, value: number) => void;
  showTooltip?: boolean;
  size?: "sm" | "md";
}) => {
  const { getColor } = useWorkspaceColorMap();
  const [openHoverCard, setOpenHoverCard] = useState(false);

  // If no feedback score and not editable, show dash
  if (!feedbackScore && !isUserFeedbackColumn) return "-";

  const shouldShowEditDropdown = isUserFeedbackColumn && onValueChange;

  // If no feedback score, show only dash with optional edit button
  if (!feedbackScore) {
    return (
      <div className="flex min-w-0 shrink-0 items-center gap-1 overflow-hidden">
        {shouldShowEditDropdown && (
          <FeedbackScoreEditDropdown
            feedbackScore={feedbackScore}
            onValueChange={onValueChange}
            size={size}
          />
        )}
        <span>-</span>
      </div>
    );
  }

  // Feedback score exists, show it with optional edit button
  const label = feedbackScore.name;
  const color = customColor || getColor(label);
  const valueByAuthor = feedbackScore.value_by_author;
  const value = feedbackScore.value;
  const category = feedbackScore.category_name;

  const isMultiValue = getIsMultiValueFeedbackScore(valueByAuthor);
  const formattedValue = formatScoreDisplay(value);

  const displayText = isMultiValue && valueByAuthor
    ? formatMultiValueDisplay(valueByAuthor, category, value)
    : category
      ? `${category} (${formattedValue})`
      : String(formattedValue);

  const tooltipContent = (() => {
    if (!showTooltip) return undefined;
    const fullPrecision = category ? `${category} (${value})` : String(value);
    if (isAggregatedScore(feedbackScore)) {
      const trialInfo = getTrialAvgTooltip(
        feedbackScore.trialValues.length,
        feedbackScore.stdDev,
      );
      return `${fullPrecision} | ${trialInfo}`;
    }
    return fullPrecision;
  })();

  return (
    <div className="flex min-w-0 shrink-0 items-center gap-1 overflow-hidden">
      {shouldShowEditDropdown && (
        <FeedbackScoreEditDropdown
          feedbackScore={feedbackScore}
          onValueChange={onValueChange}
          size={size}
        />
      )}
      {showTooltip ? (
        <TooltipWrapper content={tooltipContent}>
          <div className={size === "sm" ? "truncate" : "break-words"}>
            {displayText}
          </div>
        </TooltipWrapper>
      ) : (
        <MultiValueFeedbackScoreHoverCard
          color={color}
          valueByAuthor={valueByAuthor}
          label={label}
          value={value}
          category={category}
          open={openHoverCard}
          onOpenChange={setOpenHoverCard}
        >
          <div className={size === "sm" ? "truncate" : "break-words"}>
            {displayText}
          </div>
        </MultiValueFeedbackScoreHoverCard>
      )}
    </div>
  );
};

export default FeedbackScoreCellValue;
