import React from "react";
import { ChevronRight, Coins, PieChart } from "lucide-react";
import { cn } from "@/lib/utils";
import { formatCost } from "@/lib/money";
import { Tag } from "@/ui/tag";
import { getLaneMeta } from "./laneRegistry";
import { lanePct } from "./utils";
import { LaneSide, LaneView } from "./types";

export interface LaneCardProps {
  lane: LaneView;
  side: LaneSide;
  sideWeightTotal: number;
  onDrill?: (laneKey: string) => void;
  active?: boolean;
  compact?: boolean;
}

const LaneCard = React.forwardRef<HTMLDivElement, LaneCardProps>(
  ({ lane, side, sideWeightTotal, onDrill, active, compact }, ref) => {
    const meta = getLaneMeta(lane.key, lane.label);
    const Icon = meta.icon;
    const label = lane.label || meta.labelFallback;
    const drillable = lane.hasBreakdown && Boolean(onDrill);
    const pct = lanePct(lane.weight, sideWeightTotal);

    return (
      <div
        ref={ref}
        role={drillable ? "button" : undefined}
        tabIndex={drillable ? 0 : undefined}
        onClick={drillable ? () => onDrill?.(lane.key) : undefined}
        onKeyDown={
          drillable
            ? (event) => {
                if (event.key === "Enter" || event.key === " ") {
                  event.preventDefault();
                  onDrill?.(lane.key);
                }
              }
            : undefined
        }
        className={cn(
          "flex flex-col gap-1.5 rounded-lg border bg-background transition-colors",
          compact ? "p-2" : "p-3",
          drillable && "cursor-pointer hover:border-foreground/40",
          active && "border-foreground/60",
        )}
      >
        <div
          className={cn(
            "flex items-center gap-2",
            side === "output" && "flex-row-reverse text-right",
          )}
        >
          <div
            className="flex size-6 shrink-0 items-center justify-center rounded-md"
            style={{ backgroundColor: `${meta.color}1f`, color: meta.color }}
          >
            <Icon className="size-3.5" />
          </div>
          <span className="comet-body-s-accented truncate text-foreground">
            {label}
          </span>
          {drillable && (
            <ChevronRight
              className={cn(
                "size-3.5 shrink-0 text-muted-slate",
                side === "output" && "rotate-180",
              )}
            />
          )}
        </div>
        <div
          className={cn(
            "flex items-center gap-1.5",
            side === "output" && "flex-row-reverse",
          )}
        >
          <Tag size="sm" variant="gray" className="flex items-center gap-1">
            <PieChart className="size-3" />
            {pct.toFixed(0)}%
          </Tag>
          <Tag size="sm" variant="gray" className="flex items-center gap-1">
            <Coins className="size-3" />
            {formatCost(lane.cost)}
          </Tag>
        </div>
      </div>
    );
  },
);

LaneCard.displayName = "LaneCard";

export default LaneCard;
