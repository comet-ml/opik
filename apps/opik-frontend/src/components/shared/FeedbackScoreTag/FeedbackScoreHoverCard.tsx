import {
  HoverCard,
  HoverCardContent,
  HoverCardTrigger,
} from "@/components/ui/hover-card";
import { TraceFeedbackScore } from "@/types/traces";
import React from "react";
import isNumber from "lodash/isNumber";
import ColoredTagNew from "../ColoredTag/ColoredTagNew";
import { cn } from "@/lib/utils";
import { formatScoreDisplay } from "@/lib/feedback-scores";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";

type ScoreWithColorKey = TraceFeedbackScore & { colorKey?: string };

type FeedbackScoreHoverCardProps = {
  title?: string;
  areAggregatedScores?: boolean;
  scores: ScoreWithColorKey[];
  children: React.ReactNode;
  hidden?: boolean;
};
const FeedbackScoreHoverCard: React.FC<FeedbackScoreHoverCardProps> = ({
  title,
  areAggregatedScores,
  scores,
  children,
  hidden,
}) => {
  if (hidden) return <>{children}</>;

  return (
    <HoverCard openDelay={500}>
      <HoverCardTrigger asChild>{children}</HoverCardTrigger>
      <HoverCardContent
        side="top"
        align="start"
        className="w-[320px] border border-border px-1 py-1.5"
        collisionPadding={24}
        onClick={(event) => event.stopPropagation()}
      >
        <div
          className={cn(
            "relative size-full max-h-[40vh] max-w-[320px] overflow-auto",
            title ? "p-1 pb-0" : "py-0.5",
          )}
        >
          {title && (
            <div className="flex flex-col gap-1.5 border-b border-border px-2 pb-2">
              <div className="comet-body-xs-accented truncate leading-none text-foreground">
                {title}
              </div>
              {areAggregatedScores && (
                <div className="comet-body-xs leading-none text-slate-400">
                  Aggregated scores
                </div>
              )}
            </div>
          )}
          <div className="flex flex-col gap-1.5 pb-1 pt-1.5">
            {scores.map((tag) => {
              return (
                <div
                  key={tag.name}
                  className="flex items-center justify-between"
                >
                  <ColoredTagNew
                    label={tag.name}
                    colorKey={tag.colorKey}
                    className="min-w-0 flex-1"
                    size="sm"
                    readOnly
                  />

                  <TooltipWrapper
                    content={
                      isNumber(tag.value) ? String(tag.value) : undefined
                    }
                  >
                    <div className="comet-body-xs-accented pr-2 text-foreground">
                      {formatScoreDisplay(tag.value)}
                    </div>
                  </TooltipWrapper>
                </div>
              );
            })}
          </div>
        </div>
      </HoverCardContent>
    </HoverCard>
  );
};

export default FeedbackScoreHoverCard;
