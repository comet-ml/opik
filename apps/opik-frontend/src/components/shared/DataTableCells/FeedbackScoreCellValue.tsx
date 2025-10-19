import { generateTagVariant } from "@/lib/traces";
import MultiValueFeedbackScoreHoverCard from "../FeedbackScoreTag/MultiValueFeedbackScoreHoverCard";
import { TAG_VARIANTS_COLOR_MAP } from "@/components/ui/tag";
import { TraceFeedbackScore } from "@/types/traces";
import { useState } from "react";
import FeedbackScoreEditDropdown from "./FeedbackScoreEditDropdown";

const FeedbackScoreCellValue = ({
  isUserFeedbackColumn = false,
  feedbackScore,
  color: customColor,
  onValueChange,
}: {
  isUserFeedbackColumn?: boolean;
  feedbackScore?: TraceFeedbackScore;
  color?: string;
  onValueChange?: (value: number) => void;
}) => {
  const [openHoverCard, setOpenHoverCard] = useState(false);

  // If no feedback score and not editable, show dash
  if (!feedbackScore && !isUserFeedbackColumn) return "-";

  // If no feedback score but editable, show only the edit dropdown
  if (!feedbackScore && isUserFeedbackColumn && onValueChange) {
    return (
      <div className="group flex items-center gap-1">
        <FeedbackScoreEditDropdown
          feedbackScore={feedbackScore}
          onValueChange={onValueChange}
        />
        <span>-</span>
      </div>
    );
  }

  const label = feedbackScore!.name;
  const color =
    customColor || TAG_VARIANTS_COLOR_MAP[generateTagVariant(label)!];
  const valueByAuthor = feedbackScore!.value_by_author;
  const value = feedbackScore!.value;
  const category = feedbackScore!.category_name;

  return (
    <div className="group flex items-center gap-1">
      {onValueChange && (
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
        <div className="truncate">{feedbackScore!.value}</div>
      </MultiValueFeedbackScoreHoverCard>
    </div>
  );
};

export default FeedbackScoreCellValue;
