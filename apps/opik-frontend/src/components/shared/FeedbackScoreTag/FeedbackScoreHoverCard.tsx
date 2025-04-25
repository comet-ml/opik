import {
  HoverCard,
  HoverCardContent,
  HoverCardTrigger,
} from "@/components/ui/hover-card";
import { TraceFeedbackScore } from "@/types/traces";
import React from "react";
import ColoredTagNew from "../ColoredTag/ColoredTagNew";

type FeedbackScoreHoverCardProps = {
  name: string;
  isAverageScores?: boolean;
  isMaxScores?: boolean;
  tagList: TraceFeedbackScore[];
  children: React.ReactNode;
  hidden?: boolean;
};
const FeedbackScoreHoverCard: React.FC<FeedbackScoreHoverCardProps> = ({
  name,
  isAverageScores,
  isMaxScores,
  tagList,
  children,
  hidden,
}) => {
  if (hidden) return <>{children}</>;

  return (
    <HoverCard openDelay={500}>
      <HoverCardTrigger asChild>
        <div className="flex min-w-0 flex-1">{children}</div>
      </HoverCardTrigger>
      <HoverCardContent
        side="top"
        align="start"
        className="w-[320px] border border-border px-1 py-1.5"
        collisionPadding={24}
        onClick={(event) => event.stopPropagation()}
      >
        <div className="relative size-full max-h-[40vh] max-w-[320px] overflow-auto p-1 pb-0">
          <div className="flex flex-col gap-1.5 border-b border-border px-2 pb-2">
            <div className="comet-body-xs-accented truncate leading-none text-foreground">
              {name}
            </div>
            {isAverageScores && (
              <div className="comet-body-xs leading-none text-slate-400">
                Average scores
              </div>
            )}
            {isMaxScores && (
              <div className="comet-body-xs leading-none text-slate-400">
                Maximum scores
              </div>
            )}
          </div>
          <div className="flex flex-col gap-1.5 pb-1 pt-1.5">
            {tagList.map((tag) => {
              return (
                <div
                  key={tag.name}
                  className="flex items-center justify-between"
                >
                  <ColoredTagNew
                    label={tag.name}
                    className="min-w-0 flex-1"
                    size="sm"
                  />

                  <div className="comet-body-xs-accented pr-2 text-foreground">
                    {tag.value}
                  </div>
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
