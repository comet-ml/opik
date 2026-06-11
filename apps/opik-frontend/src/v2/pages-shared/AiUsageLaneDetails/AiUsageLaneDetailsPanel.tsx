import React, { useEffect, useMemo, useState } from "react";
import { Box, ChevronsRight, PieChart, TrendingDown } from "lucide-react";
import useAiSpendLaneBreakdown from "@/api/ai-spend/useAiSpendLaneBreakdown";
import useAiSpendRecommendations from "@/api/ai-spend/useAiSpendRecommendations";
import ResizableSidePanel from "@/shared/ResizableSidePanel/ResizableSidePanel";
import ProgressBar from "@/shared/ProgressBar/ProgressBar";
import TokenCount from "@/shared/TokenCount/TokenCount";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { Skeleton } from "@/ui/skeleton";
import { Tag } from "@/ui/tag";
import { Button } from "@/ui/button";
import { Tabs, TabsList, TabsTrigger } from "@/ui/tabs";
import { cn } from "@/lib/utils";
import { formatCost } from "@/lib/money";
import { getSpendInterval, SPEND_WINDOWS, SpendWindow } from "@/lib/aiSpend";
import { getLaneMeta } from "@/v2/pages-shared/AiUsageBreakdown/laneRegistry";
import { laneWeight, lanePct } from "@/v2/pages-shared/AiUsageBreakdown/utils";
import RecommendationCard from "@/v2/pages-shared/AiUsageRecommendations/RecommendationCard";

interface AiUsageLaneDetailsPanelProps {
  laneKey: string | null;
  laneLabel?: string;
  projectName: string;
  defaultWindow: SpendWindow;
  userUuid?: string;
  userName?: string;
  showRecommendations?: boolean;
  onClose: () => void;
}

const AiUsageLaneDetailsPanel: React.FC<AiUsageLaneDetailsPanelProps> = ({
  laneKey,
  laneLabel,
  projectName,
  defaultWindow,
  userUuid,
  userName,
  showRecommendations = true,
  onClose,
}) => {
  const [windowDays, setWindowDays] = useState<SpendWindow>(defaultWindow);

  useEffect(() => {
    if (laneKey) setWindowDays(defaultWindow);
  }, [laneKey, defaultWindow]);

  const { intervalStart, intervalEnd } = useMemo(
    () => getSpendInterval(windowDays),
    [windowDays],
  );

  const breakdownQuery = useAiSpendLaneBreakdown(
    {
      laneKey: laneKey ?? "",
      projectName,
      intervalStart,
      intervalEnd,
      userUuid,
    },
    { enabled: Boolean(laneKey) },
  );
  const breakdown = breakdownQuery.data;

  const recommendationsQuery = useAiSpendRecommendations(
    { projectName, intervalStart, intervalEnd, userUuid },
    { enabled: Boolean(laneKey) && showRecommendations },
  );
  const recommendations = (recommendationsQuery.data?.items ?? []).filter(
    (item) => item.related_lane_key === laneKey,
  );
  const recommendationsSavings = recommendations.reduce(
    (acc, item) => acc + (item.est_saving ?? 0),
    0,
  );

  const meta = getLaneMeta(laneKey ?? "", laneLabel);
  const LaneIcon = meta.icon;
  const title = breakdown?.title ?? laneLabel ?? meta.labelFallback;

  const totalWeight = Math.max(...(breakdown?.items ?? []).map(laneWeight), 0);
  const weightSum = (breakdown?.items ?? []).reduce(
    (acc, item) => acc + laneWeight(item),
    0,
  );

  const header = (
    <div className="flex w-full items-center justify-between gap-2">
      <div className="flex min-w-0 items-center gap-2">
        <Button variant="ghost" size="icon-2xs" onClick={onClose}>
          <ChevronsRight />
          <span className="sr-only">Close</span>
        </Button>
        <div
          className={cn(
            "flex size-4 shrink-0 items-center justify-center rounded-sm",
            meta.iconColor,
          )}
          style={{ backgroundColor: meta.color }}
        >
          <LaneIcon className="size-2.5" />
        </div>
        <div className="flex min-w-0 items-baseline gap-1.5">
          <TooltipWrapper content={title}>
            <span className="comet-body-s-accented shrink-0 truncate text-foreground">
              {title}
            </span>
          </TooltipWrapper>
          {userName && (
            <TooltipWrapper content={userName}>
              <div className="comet-body-xs flex min-w-0 items-baseline text-muted-slate">
                <span className="shrink-0">(</span>
                <span className="truncate">{userName}</span>
                <span className="shrink-0">)</span>
              </div>
            </TooltipWrapper>
          )}
        </div>
      </div>
      <Tabs
        value={String(windowDays)}
        onValueChange={(value) => setWindowDays(Number(value) as SpendWindow)}
      >
        <TabsList variant="segmented">
          {SPEND_WINDOWS.map((w) => (
            <TabsTrigger key={w} variant="segmented" value={String(w)}>
              {w}d
            </TabsTrigger>
          ))}
        </TabsList>
      </Tabs>
    </div>
  );

  const renderBreakdown = () => {
    if (breakdownQuery.isPending) {
      return (
        <div className="flex flex-col gap-3">
          {[0, 1, 2, 3, 4].map((i) => (
            <Skeleton key={i} className="h-4 w-full" />
          ))}
        </div>
      );
    }
    if (breakdownQuery.isError || !breakdown) {
      return (
        <div className="comet-body-s text-muted-slate">
          Detailed breakdown isn&apos;t available yet.
        </div>
      );
    }
    if (breakdown.items.length === 0) {
      return (
        <div className="comet-body-s text-muted-slate">
          No items in the selected period.
        </div>
      );
    }
    return (
      <div className="grid grid-cols-[minmax(0,240px)_minmax(64px,1fr)_auto] items-center gap-x-4 gap-y-3">
        {breakdown.items.map((item) => {
          const weight = laneWeight(item);
          const pct = lanePct(weight, weightSum);
          const barPct = lanePct(weight, totalWeight);
          return (
            <React.Fragment key={item.label}>
              <TooltipWrapper content={item.label}>
                <span className="comet-body-xs min-w-0 truncate text-foreground">
                  {item.label}
                </span>
              </TooltipWrapper>
              <ProgressBar
                value={barPct}
                color={meta.color}
                className="w-full max-w-[240px]"
              />
              <div className="flex items-center justify-end gap-3">
                <span className="comet-body-xs flex items-center gap-1 text-muted-slate">
                  <PieChart className="size-3" />
                  {pct.toFixed(1)}%
                </span>
                <TokenCount
                  tokens={item.total_tokens}
                  className="comet-body-xs text-muted-slate"
                />
              </div>
            </React.Fragment>
          );
        })}
      </div>
    );
  };

  return (
    <ResizableSidePanel
      panelId="ai-spend-lane-details"
      entity="breakdown"
      open={Boolean(laneKey)}
      onClose={onClose}
      header={header}
      initialWidth={0.4}
      minWidth={480}
    >
      <div className="flex size-full flex-col gap-4 overflow-y-auto p-4">
        <div className="flex flex-col gap-2">
          {breakdown?.subtitle && (
            <p className="comet-body-s text-muted-slate">
              {breakdown.subtitle}
            </p>
          )}
          <div className="flex items-center gap-2">
            <TokenCount
              tokens={breakdown?.total_tokens ?? 0}
              showLabel
              className="comet-body-s text-muted-slate"
              iconClassName="size-3.5"
            />
            <span className="comet-body-s flex items-center gap-1 text-muted-slate">
              <Box className="size-3.5" />
              {breakdown?.item_count ?? 0} items
            </span>
          </div>
        </div>

        <div className="flex flex-col gap-1.5 rounded-md border bg-background px-3 py-2">
          <span className="comet-body-xs-accented text-muted-slate">
            Cost breakdown
          </span>
          {renderBreakdown()}
        </div>

        {showRecommendations && recommendations.length > 0 && (
          <div className="flex flex-col gap-3 rounded-md border bg-background px-3 py-2">
            <div className="flex items-center justify-between gap-2">
              <span className="comet-body-xs-accented text-muted-slate">
                Potential savings
              </span>
              <Tag
                variant="green"
                size="sm"
                className="flex items-center gap-1"
              >
                <TrendingDown className="size-3" />
                {formatCost(recommendationsSavings)}
              </Tag>
            </div>
            <div className="flex flex-col gap-2">
              {recommendations.map((rec) => (
                <RecommendationCard
                  key={rec.id}
                  recommendation={rec}
                  variant="compact"
                />
              ))}
            </div>
          </div>
        )}
      </div>
    </ResizableSidePanel>
  );
};

export default AiUsageLaneDetailsPanel;
