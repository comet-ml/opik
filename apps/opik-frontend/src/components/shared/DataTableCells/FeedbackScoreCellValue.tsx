import { generateTagVariant } from "@/lib/traces";
import MultiValueFeedbackScoreHoverCard from "../FeedbackScoreTag/MultiValueFeedbackScoreHoverCard";
import { TAG_VARIANTS_COLOR_MAP } from "@/components/ui/tag";
import { TraceFeedbackScore } from "@/types/traces";
import { useState } from "react";

const FeedbackScoreCellValue = ({
  feedbackScore,
  color: customColor,
}: {
  feedbackScore?: TraceFeedbackScore;
  color?: string;
}) => {
  const [openHoverCard, setOpenHoverCard] = useState(false);

  if (!feedbackScore) return "-";

  const label = feedbackScore.name;
  const color =
    customColor || TAG_VARIANTS_COLOR_MAP[generateTagVariant(label)!];
  const valueByAuthor = feedbackScore.value_by_author;
  const value = feedbackScore.value;
  const category = feedbackScore.category_name;

  return (
    <MultiValueFeedbackScoreHoverCard
      color={color}
      valueByAuthor={valueByAuthor}
      label={label}
      value={value}
      category={category}
      open={openHoverCard}
      onOpenChange={setOpenHoverCard}
    >
      <div className="truncate">{feedbackScore.value}</div>
    </MultiValueFeedbackScoreHoverCard>
  );
};

export default FeedbackScoreCellValue;
