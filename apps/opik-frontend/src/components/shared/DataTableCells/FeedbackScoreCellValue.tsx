import { generateTagVariant } from "@/lib/traces";
import MultiValueFeedbackScoreHoverCard from "../FeedbackScoreTag/MultiValueFeedbackScoreHoverCard";
import { TAG_VARIANTS_COLOR_MAP } from "@/components/ui/tag";
import { TraceFeedbackScore } from "@/types/traces";
import { useState } from "react";
import FeedbackScoreEditDropdown from "./FeedbackScoreEditDropdown";
import CellTooltipWrapper from "./CellTooltipWrapper";

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
  const color =
    customColor || TAG_VARIANTS_COLOR_MAP[generateTagVariant(label)!];
  const valueByAuthor = feedbackScore.value_by_author;
  const value = feedbackScore.value;
  const category = feedbackScore.category_name;

  const displayText = category ? `${category} (${value})` : String(value);

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
        <CellTooltipWrapper content={displayText}>
          <div className="truncate">{displayText}</div>
        </CellTooltipWrapper>
      </MultiValueFeedbackScoreHoverCard>
    </div>
  );
};

export default FeedbackScoreCellValue;
