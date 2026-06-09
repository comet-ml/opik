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
  onDrill?: (laneKey: string) => void;
  activeLaneKey?: string | null;
  compact?: boolean;
  registerRef: (index: number, el: HTMLDivElement | null) => void;
}

const BreakdownColumn: React.FC<BreakdownColumnProps> = ({
  title,
  side,
  totalCost,
  lanes,
  onDrill,
  activeLaneKey,
  compact,
  registerRef,
}) => {
  const weightTotal = computeSideWeightTotal(lanes);

  return (
    <div className="flex flex-col gap-2">
      <div
        className={cn(
          "flex items-center justify-between gap-2",
          side === "output" && "flex-row-reverse text-right",
        )}
      >
        <span className="comet-body-s-accented text-muted-slate">{title}</span>
        <span className="comet-body-s text-muted-slate">
          {formatCost(totalCost)}
        </span>
      </div>
      {lanes.length === 0 ? (
        <div className="comet-body-xs rounded-lg border border-dashed p-3 text-center text-muted-slate">
          No data
        </div>
      ) : (
        lanes.map((lane, index) => (
          <LaneCard
            key={lane.key}
            ref={(el) => registerRef(index, el)}
            lane={lane}
            side={side}
            sideWeightTotal={weightTotal}
            onDrill={onDrill}
            active={activeLaneKey === lane.key}
            compact={compact}
          />
        ))
      )}
    </div>
  );
};

export default BreakdownColumn;
