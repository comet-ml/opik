import React from "react";
import { PenLine } from "lucide-react";

import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import FeedbackScoreTag from "@/components/shared/FeedbackScoreTag/FeedbackScoreTag";
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
    <div className={cn("flex min-h-7 items-center gap-2", className)}>
      <TooltipWrapper content="Feedback scores">
        <PenLine className="mx-1 size-4 shrink-0 text-muted-slate" />
      </TooltipWrapper>
      <div className="flex gap-1 overflow-x-auto">
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
