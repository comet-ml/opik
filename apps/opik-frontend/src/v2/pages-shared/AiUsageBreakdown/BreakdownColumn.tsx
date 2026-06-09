import React from "react";
import { cn } from "@/lib/utils";
import { formatCost } from "@/lib/money";
import LaneCard from "./LaneCard";
import { sideWeightTotal as computeSideWeightTotal } from "./utils";
import { LaneSide, LaneView } from "./types";

interface BreakdownColumnProps {
  title: string;
  side: LaneSide;
  totalCost: number | null;
  lanes: LaneView[];
  onLaneClick?: (laneKey: string) => void;
  onLaneHover?: (laneKey: string | null) => void;
  activeLaneKey?: string | null;
  recommendationLaneKeys?: Set<string>;
  renderLaneWrapper?: (
    lane: LaneView,
    card: React.ReactNode,
  ) => React.ReactNode;
  compact?: boolean;
  registerRef: (index: number, el: HTMLDivElement | null) => void;
}

const BreakdownColumn: React.FC<BreakdownColumnProps> = ({
  title,
  side,
  totalCost,
  lanes,
  onLaneClick,
  onLaneHover,
  activeLaneKey,
  recommendationLaneKeys,
  renderLaneWrapper,
  compact,
  registerRef,
}) => {
  const weightTotal = computeSideWeightTotal(lanes);
  const reverse = side === "output";

  return (
    <div className="flex flex-col gap-2">
      <div
        className={cn(
          "flex items-center justify-between gap-2",
          reverse && "flex-row-reverse",
        )}
      >
        <span className="comet-body-s text-foreground">{title}</span>
        <span className="comet-body-s text-light-slate">
          {formatCost(totalCost)}
        </span>
      </div>
      {lanes.length === 0 ? (
        <div className="comet-body-xs rounded-md border border-dashed p-3 text-center text-muted-slate">
          No data
        </div>
      ) : (
        lanes.map((lane, index) => {
          const card = (
            <LaneCard
              ref={(el) => registerRef(index, el)}
              lane={lane}
              side={side}
              sideWeightTotal={weightTotal}
              onLaneClick={onLaneClick}
              onLaneHover={onLaneHover}
              active={activeLaneKey === lane.key}
              showRecommendation={recommendationLaneKeys?.has(lane.key)}
              compact={compact}
            />
          );
          return (
            <React.Fragment key={lane.key}>
              {renderLaneWrapper ? renderLaneWrapper(lane, card) : card}
            </React.Fragment>
          );
        })
      )}
    </div>
  );
};

export default BreakdownColumn;
