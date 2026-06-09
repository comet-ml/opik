import React from "react";
import { Coins, Lightbulb, PieChart } from "lucide-react";
import { cn } from "@/lib/utils";
import { formatCost } from "@/lib/money";
import { getLaneMeta } from "./laneRegistry";
import { lanePct } from "./utils";
import { LaneSide, LaneView } from "./types";

export interface LaneCardProps extends React.HTMLAttributes<HTMLDivElement> {
  lane: LaneView;
  side: LaneSide;
  sideWeightTotal: number;
  onLaneClick?: (laneKey: string) => void;
  onLaneHover?: (laneKey: string | null) => void;
  active?: boolean;
  showRecommendation?: boolean;
  compact?: boolean;
}

const LaneCard = React.forwardRef<HTMLDivElement, LaneCardProps>(
  (
    {
      lane,
      side,
      sideWeightTotal,
      onLaneClick,
      onLaneHover,
      active,
      showRecommendation,
      compact,
      ...rest
    },
    ref,
  ) => {
    const meta = getLaneMeta(lane.key, lane.label);
    const Icon = meta.icon;
    const label = lane.label || meta.labelFallback;
    const drillable = lane.hasBreakdown && Boolean(onLaneClick);
    const pct = lanePct(lane.weight, sideWeightTotal);
    const reverse = side === "output";
    const hasData = lane.weight > 0;

    return (
      <div
        ref={ref}
        {...rest}
        role={drillable ? "button" : undefined}
        tabIndex={drillable ? 0 : undefined}
        onClick={drillable ? () => onLaneClick?.(lane.key) : undefined}
        onMouseEnter={() => {
          if (hasData) onLaneHover?.(lane.key);
        }}
        onMouseLeave={() => onLaneHover?.(null)}
        onKeyDown={
          drillable
            ? (event) => {
                if (event.key === "Enter" || event.key === " ") {
                  event.preventDefault();
                  onLaneClick?.(lane.key);
                }
              }
            : undefined
        }
        style={
          {
            "--lane-color": meta.color,
            "--lane-color-soft": `color-mix(in srgb, ${meta.color} 10%, hsl(var(--background)))`,
          } as React.CSSProperties
        }
        className={cn(
          "flex flex-col gap-0.5 rounded-md border bg-background p-2 transition-colors",
          drillable && "cursor-pointer",
          active && "border-[var(--lane-color)] bg-[var(--lane-color-soft)]",
          compact && "gap-0",
        )}
      >
        <div
          className={cn(
            "flex items-center gap-2",
            reverse && "flex-row-reverse",
          )}
        >
          <div
            className="flex size-4 shrink-0 items-center justify-center rounded-sm text-white"
            style={{ backgroundColor: meta.color }}
          >
            <Icon className="size-2.5" />
          </div>
          <span
            className={cn(
              "comet-body-xs-accented min-w-0 flex-1 truncate text-foreground",
              reverse && "text-right",
            )}
          >
            {label}
          </span>
          {showRecommendation && (
            <Lightbulb className="size-3 shrink-0 text-light-slate" />
          )}
        </div>
        <div
          className={cn(
            "flex items-center gap-3 py-0.5",
            reverse && "flex-row-reverse",
          )}
        >
          <span className="comet-body-xs flex items-center gap-1 text-muted-slate">
            <PieChart className="size-3" />
            {pct.toFixed(0)}%
          </span>
          <span className="comet-body-xs flex items-center gap-1 text-muted-slate">
            <Coins className="size-3" />
            {formatCost(lane.cost)}
          </span>
        </div>
      </div>
    );
  },
);

LaneCard.displayName = "LaneCard";

export default LaneCard;
