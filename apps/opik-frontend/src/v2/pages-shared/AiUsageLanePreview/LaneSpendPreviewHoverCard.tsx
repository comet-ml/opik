import React from "react";
import { Coins, PieChart } from "lucide-react";
import useAiSpendLaneBreakdown, {
  AiSpendBreakdownItemApi,
} from "@/api/ai-spend/useAiSpendLaneBreakdown";
import { HoverCard, HoverCardContent, HoverCardTrigger } from "@/ui/hover-card";
import { Button } from "@/ui/button";
import { Skeleton } from "@/ui/skeleton";
import ProgressBar from "@/shared/ProgressBar/ProgressBar";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { formatCost } from "@/lib/money";
import { getLaneMeta } from "@/v2/pages-shared/AiUsageBreakdown/laneRegistry";

const TOP_N = 5;
const MS_PER_DAY = 86_400_000;

interface LaneSpendPreviewHoverCardProps {
  laneKey: string;
  projectName: string;
  intervalStart: string;
  intervalEnd: string;
  userUuid?: string;
  onViewAll?: (laneKey: string) => void;
  children: React.ReactNode;
}

const itemWeight = (item: AiSpendBreakdownItemApi): number =>
  typeof item.total_estimated_cost === "number" && item.total_estimated_cost > 0
    ? item.total_estimated_cost
    : item.total_tokens;

const PreviewBody: React.FC<{
  laneKey: string;
  projectName: string;
  intervalStart: string;
  intervalEnd: string;
  userUuid?: string;
  onViewAll?: (laneKey: string) => void;
}> = ({
  laneKey,
  projectName,
  intervalStart,
  intervalEnd,
  userUuid,
  onViewAll,
}) => {
  const { data, isPending } = useAiSpendLaneBreakdown({
    laneKey,
    projectName,
    intervalStart,
    intervalEnd,
    userUuid,
  });

  const days = Math.max(
    1,
    Math.round(
      (Date.parse(intervalEnd) - Date.parse(intervalStart)) / MS_PER_DAY,
    ),
  );

  if (isPending) {
    return (
      <div className="flex flex-col gap-2">
        <Skeleton className="h-4 w-40" />
        {[0, 1, 2, 3, 4].map((i) => (
          <Skeleton key={i} className="h-4 w-full" />
        ))}
      </div>
    );
  }

  const items = data?.items ?? [];
  if (items.length === 0) {
    return (
      <div className="comet-body-xs text-muted-slate">
        No spend items in this period.
      </div>
    );
  }

  const totalWeight = items.reduce((acc, item) => acc + itemWeight(item), 0);
  const top = [...items]
    .sort((a, b) => itemWeight(b) - itemWeight(a))
    .slice(0, TOP_N);
  const itemCount = data?.item_count ?? items.length;
  const hasMore = itemCount > top.length;
  const barColor = getLaneMeta(laneKey).color;

  return (
    <div className="flex flex-col">
      <div className="px-2 pb-1 pt-0.5">
        <span className="comet-body-xs-accented text-foreground">
          Top spend items in the last {days} days
        </span>
      </div>
      <div className="my-1 h-px w-full bg-border" />
      <div className="grid grid-cols-[80px_auto_1fr] items-center gap-x-2 gap-y-1.5 px-2 py-1">
        {top.map((item) => {
          const pct =
            totalWeight > 0 ? (itemWeight(item) / totalWeight) * 100 : 0;
          return (
            <React.Fragment key={item.label}>
              <TooltipWrapper content={item.label}>
                <span className="comet-body-xs truncate text-foreground">
                  {item.label}
                </span>
              </TooltipWrapper>
              <ProgressBar value={pct} color={barColor} />
              <div className="flex items-center justify-end gap-2">
                <span className="comet-body-xs flex items-center gap-1 text-muted-slate">
                  <PieChart className="size-3" />
                  {pct.toFixed(1)}%
                </span>
                <span className="comet-body-xs flex items-center gap-1 text-muted-slate">
                  <Coins className="size-3" />
                  {formatCost(item.total_estimated_cost)}
                </span>
              </div>
            </React.Fragment>
          );
        })}
      </div>
      {hasMore && (
        <>
          <div className="my-1 h-px w-full bg-border" />
          <Button
            variant="link"
            size="sm"
            className="comet-body-xs h-6 w-full justify-center px-2"
            onClick={() => onViewAll?.(laneKey)}
          >
            View all ({itemCount})
          </Button>
        </>
      )}
    </div>
  );
};

const LaneSpendPreviewHoverCard: React.FC<LaneSpendPreviewHoverCardProps> = ({
  laneKey,
  projectName,
  intervalStart,
  intervalEnd,
  userUuid,
  onViewAll,
  children,
}) => {
  return (
    <HoverCard openDelay={150} closeDelay={100}>
      <HoverCardTrigger asChild>{children}</HoverCardTrigger>
      <HoverCardContent align="center" sideOffset={8} className="w-[360px] p-2">
        <PreviewBody
          laneKey={laneKey}
          projectName={projectName}
          intervalStart={intervalStart}
          intervalEnd={intervalEnd}
          userUuid={userUuid}
          onViewAll={onViewAll}
        />
      </HoverCardContent>
    </HoverCard>
  );
};

export default LaneSpendPreviewHoverCard;
