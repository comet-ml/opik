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
  footer,
  size = "md",
}: {
  isUserFeedbackColumn?: boolean;
  feedbackScore?: TraceFeedbackScore;
  color?: string;
  onValueChange?: (name: string, value: number) => void;
  footer?: string;
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

  const displayText =
    isMultiValue && valueByAuthor
      ? formatMultiValueDisplay(valueByAuthor, category, value)
      : category
        ? `${category} (${formattedValue})`
        : String(formattedValue);

  return (
    <div className="flex min-w-0 shrink-0 items-center gap-1 overflow-hidden">
      {shouldShowEditDropdown && (
        <FeedbackScoreEditDropdown
          feedbackScore={feedbackScore}
          onValueChange={onValueChange}
          size={size}
        />
      )}
      <MultiValueFeedbackScoreHoverCard
        color={color}
        valueByAuthor={valueByAuthor}
        label={label}
        value={value}
        category={category}
        footer={footer}
        open={openHoverCard}
        onOpenChange={setOpenHoverCard}
      >
        <div className={size === "sm" ? "truncate" : "break-words"}>
          {displayText}
        </div>
      </MultiValueFeedbackScoreHoverCard>
    </div>
  );
};

export default FeedbackScoreCellValue;
