import React from "react";
import { PenLine } from "lucide-react";

import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import FeedbackScoreTag from "@/shared/FeedbackScoreTag/FeedbackScoreTag";
import { FeedbackScoreDisplay } from "@/types/shared";
import { cn } from "@/lib/utils";

type FeedbackScoresListProps = {
  scores: FeedbackScoreDisplay[];
  className?: string;
};

const FeedbackScoresList: React.FunctionComponent<FeedbackScoresListProps> = ({
  scores,
  className,
}) => {
  if (scores.length === 0) return null;

  return (
    <div className={cn("flex min-h-7 items-center", className)}>
      <TooltipWrapper content="Feedback scores">
        <PenLine className="mx-1 size-3.5 shrink-0 text-muted-slate" />
      </TooltipWrapper>
      <div className="flex gap-1.5 overflow-x-auto">
        {scores.map((score) => (
          <FeedbackScoreTag
            key={score.name + score.value}
            label={score.name}
            colorKey={score.colorKey}
            value={score.value}
          />
        ))}
      </div>
    </div>
  );
};

export default FeedbackScoresList;
