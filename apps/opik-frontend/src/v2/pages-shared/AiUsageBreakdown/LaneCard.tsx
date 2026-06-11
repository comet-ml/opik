import React from "react";
import { Lightbulb, PieChart } from "lucide-react";
import { cn } from "@/lib/utils";
import TokenCount from "@/shared/TokenCount/TokenCount";
import { getLaneMeta } from "./laneRegistry";
import { lanePct } from "./utils";
import { LaneView } from "./types";

export interface LaneCardProps extends React.HTMLAttributes<HTMLDivElement> {
  lane: LaneView;
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
    const hasData = lane.weight > 0;
    const actionable = drillable && hasData;

    return (
      <div
        ref={ref}
        {...rest}
        role={actionable ? "button" : undefined}
        tabIndex={actionable ? 0 : undefined}
        onClick={actionable ? () => onLaneClick?.(lane.key) : undefined}
        onKeyDown={
          actionable
            ? (event) => {
                if (event.key === "Enter" || event.key === " ") {
                  event.preventDefault();
                  onLaneClick?.(lane.key);
                }
              }
            : undefined
        }
        onMouseEnter={() => {
          if (actionable) onLaneHover?.(lane.key);
        }}
        onMouseLeave={() => onLaneHover?.(null)}
        style={
          {
            "--lane-color": meta.color,
            "--lane-color-soft": `color-mix(in srgb, ${meta.color} 10%, hsl(var(--background)))`,
          } as React.CSSProperties
        }
        className={cn(
          "flex flex-col gap-0.5 rounded-md border bg-background p-2 transition-colors",
          actionable && "cursor-pointer",
          active && "border-[var(--lane-color)] bg-[var(--lane-color-soft)]",
          compact && "gap-0",
        )}
      >
        <div className="flex items-center gap-2">
          <div
            className={cn(
              "flex size-4 shrink-0 items-center justify-center rounded-sm",
              meta.iconColor,
            )}
            style={{ backgroundColor: meta.color }}
          >
            <Icon className="size-2.5" />
          </div>
          <span className="comet-body-xs-accented min-w-0 flex-1 truncate text-foreground">
            {label}
          </span>
          {showRecommendation && (
            <Lightbulb className="size-3 shrink-0 text-light-slate" />
          )}
        </div>
        <div className="flex items-center gap-3 py-0.5">
          <span className="comet-body-xs flex items-center gap-1 text-muted-slate">
            <PieChart className="size-3" />
            {pct.toFixed(0)}%
          </span>
          <TokenCount
            tokens={lane.tokens}
            className="comet-body-xs text-muted-slate"
          />
        </div>
      </div>
    );
  },
);

LaneCard.displayName = "LaneCard";

export default LaneCard;
