import MultiValueFeedbackScoreHoverCard from "../FeedbackScoreTag/MultiValueFeedbackScoreHoverCard";
import { TraceFeedbackScore } from "@/types/traces";
import { useState } from "react";
import FeedbackScoreEditDropdown from "./FeedbackScoreEditDropdown";
import {
  formatScoreDisplay,
  getIsMultiValueFeedbackScore,
} from "@/lib/feedback-scores";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import useWorkspaceColorMap from "@/hooks/useWorkspaceColorMap";

const FeedbackScoreCellValue = ({
  isUserFeedbackColumn = false,
  feedbackScore,
  color: customColor,
  onValueChange,
}: {
  isUserFeedbackColumn?: boolean;
  feedbackScore?: TraceFeedbackScore;
  color?: string;
  onValueChange?: (name: string, value: number) => void;
}) => {
  const { getColor } = useWorkspaceColorMap();
  const [openHoverCard, setOpenHoverCard] = useState(false);

  // If no feedback score and not editable, show dash
  if (!feedbackScore && !isUserFeedbackColumn) return "-";

  const shouldShowEditDropdown = isUserFeedbackColumn && onValueChange;

  // If no feedback score, show only dash with optional edit button
  if (!feedbackScore) {
    return (
      <div className="flex min-w-0 items-center gap-1 overflow-hidden">
        {shouldShowEditDropdown && (
          <FeedbackScoreEditDropdown
            feedbackScore={feedbackScore}
            onValueChange={onValueChange}
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

  const formattedValue = formatScoreDisplay(value);
  const displayText = category
    ? `${category} (${formattedValue})`
    : String(formattedValue);
  const fullPrecisionText = category ? `${category} (${value})` : String(value);
  const showTooltip = !getIsMultiValueFeedbackScore(valueByAuthor);

  return (
    <div className="flex min-w-0 items-center gap-1 overflow-hidden">
      {shouldShowEditDropdown && (
        <FeedbackScoreEditDropdown
          feedbackScore={feedbackScore}
          onValueChange={onValueChange}
        />
      )}
      <MultiValueFeedbackScoreHoverCard
        color={color}
        valueByAuthor={valueByAuthor}
        label={label}
        value={value}
        category={category}
        open={openHoverCard}
        onOpenChange={setOpenHoverCard}
      >
        {showTooltip ? (
          <TooltipWrapper content={fullPrecisionText}>
            <div className="truncate">{displayText}</div>
          </TooltipWrapper>
        ) : (
          <div className="truncate">{displayText}</div>
        )}
      </MultiValueFeedbackScoreHoverCard>
    </div>
  );
};

export default FeedbackScoreCellValue;
