import React, { useEffect, useMemo, useState } from "react";
import { ChevronsRight, TrendingDown } from "lucide-react";
import useAiSpendLaneBreakdown, {
  AiSpendBreakdownItemApi,
} from "@/api/ai-spend/useAiSpendLaneBreakdown";
import useAiSpendRecommendations from "@/api/ai-spend/useAiSpendRecommendations";
import ResizableSidePanel from "@/shared/ResizableSidePanel/ResizableSidePanel";
import ProgressBar from "@/shared/ProgressBar/ProgressBar";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { Skeleton } from "@/ui/skeleton";
import { Tag } from "@/ui/tag";
import { Button } from "@/ui/button";
import { Tabs, TabsList, TabsTrigger } from "@/ui/tabs";
import { cn, formatNumberInK } from "@/lib/utils";
import { formatCost } from "@/lib/money";
import { tiersCost } from "@/api/ai-spend/claudePricing";
import { getSpendInterval, SPEND_WINDOWS, SpendWindow } from "@/lib/aiSpend";
import { getLaneMeta } from "@/v2/pages-shared/AiUsageBreakdown/laneRegistry";
import { laneWeight, lanePct } from "@/v2/pages-shared/AiUsageBreakdown/utils";
import useSavingsPricer from "@/api/ai-spend/useSavingsPricer";
import RecommendationCard from "@/v2/pages-shared/AiUsageRecommendations/RecommendationCard";

const PROMPT_SIZE_ORDER = ["small", "medium", "large", "xlarge"];

// Counts and tokens run on different clocks: count increments only when a new
// event happens, while tokens re-bill the whole context every turn.
const COUNT_TOOLTIP =
  "Events in the selected window. Tokens and cost also include re-billing of earlier context on every turn, so an item can have cost without new events.";

const formatCount = (count: number, unit: string) =>
  `${count} ${count === 1 ? unit : `${unit}s`}`;

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
  const priceSavings = useSavingsPricer({
    projectName,
    intervalStart,
    intervalEnd,
    userUuid,
  });
  const recommendationsSavings = recommendations.reduce(
    (acc, item) => acc + (priceSavings(item.estimated_savings_tokens) ?? 0),
    0,
  );

  const meta = getLaneMeta(laneKey ?? "", laneLabel);
  const LaneIcon = meta.icon;
  const title = breakdown?.title ?? laneLabel ?? meta.labelFallback;

  const totalTokens = breakdown?.total_tokens ?? 0;
  const cost = breakdown ? tiersCost(breakdown.by_model) : null;

  const renderItems = (items: AiSpendBreakdownItemApi[], unit?: string) => {
    const totalWeight = Math.max(...items.map(laneWeight), 0);
    const weightSum = items.reduce((acc, item) => acc + laneWeight(item), 0);
    // No unit means counts are structurally 0 for this lane - hide the column.
    const countUnit = unit ?? breakdown?.item_unit;
    const hasCounts = items.some((item) => item.count != null);
    // The BE emits definition/usage sums for every lane (0 when the lane has
    // no definition concept) - only lanes declaring the split render it.
    const hasDefinitionSplit = Boolean(meta.definitionSplit);
    return (
      <div className="grid grid-cols-[minmax(0,160px)_minmax(80px,1fr)_auto] items-center gap-x-4 gap-y-3">
        {items.map((item) => {
          const weight = laneWeight(item);
          const pct = lanePct(weight, weightSum);
          const barPct = lanePct(weight, totalWeight);
          const itemCost = tiersCost(item.by_model);
          return (
            <React.Fragment key={item.label}>
              <TooltipWrapper content={item.label}>
                <span className="comet-body-xs min-w-0 truncate text-foreground">
                  {item.label}
                </span>
              </TooltipWrapper>
              {hasDefinitionSplit &&
              item.definition_tokens != null &&
              item.usage_tokens != null ? (
                <TooltipWrapper
                  content={`Definition ${item.definition_tokens.toLocaleString()} tok · Usage ${item.usage_tokens.toLocaleString()} tok`}
                >
                  <div className="relative h-1.5 w-full shrink-0 overflow-hidden rounded-full bg-border">
                    <div
                      className="absolute inset-y-0 flex"
                      style={{ width: `${barPct}%` }}
                    >
                      <div
                        style={{
                          width: `${lanePct(item.definition_tokens, weight)}%`,
                          backgroundColor: meta.color,
                          opacity: 0.35,
                        }}
                      />
                      <div
                        className="rounded-r-full"
                        style={{
                          width: `${lanePct(item.usage_tokens, weight)}%`,
                          backgroundColor: meta.color,
                        }}
                      />
                    </div>
                  </div>
                </TooltipWrapper>
              ) : (
                <ProgressBar
                  value={barPct}
                  color={meta.color}
                  className="w-full"
                />
              )}
              <div className="flex items-center justify-end gap-3">
                <TooltipWrapper
                  content={`${item.total_tokens.toLocaleString()} tokens`}
                >
                  <span className="comet-body-xs-accented w-12 shrink-0 text-right text-foreground">
                    {itemCost != null
                      ? formatCost(itemCost)
                      : formatNumberInK(item.total_tokens)}
                  </span>
                </TooltipWrapper>
                <span className="comet-body-xs w-11 shrink-0 text-right text-light-slate">
                  {pct.toFixed(1)}%
                </span>
                {countUnit && hasCounts && (
                  <TooltipWrapper content={COUNT_TOOLTIP}>
                    <span className="comet-body-xs w-20 shrink-0 text-right text-light-slate">
                      {item.count != null
                        ? formatCount(item.count, countUnit)
                        : ""}
                    </span>
                  </TooltipWrapper>
                )}
              </div>
            </React.Fragment>
          );
        })}
      </div>
    );
  };

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
    // User-prompt buckets read as a size axis, so order them small→xlarge
    // rather than by volume. Unknown labels (e.g. an "Other" row) sink last.
    const items =
      laneKey === "user_prompts"
        ? [...breakdown.items].sort((a, b) => {
            const rank = (label: string) => {
              const i = PROMPT_SIZE_ORDER.indexOf(label);
              return i === -1 ? PROMPT_SIZE_ORDER.length : i;
            };
            return rank(a.label) - rank(b.label);
          })
        : breakdown.items;
    return renderItems(items, breakdown.items_unit);
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
        <div className="flex flex-col gap-1.5">
          <div className="flex items-baseline gap-1.5">
            {cost != null ? (
              <>
                <span className="comet-title-m text-foreground">
                  {formatCost(cost)}
                </span>
                <TooltipWrapper
                  content={`${totalTokens.toLocaleString()} tokens`}
                >
                  <span className="comet-body-s text-muted-slate">
                    {formatNumberInK(totalTokens)} tokens
                  </span>
                </TooltipWrapper>
              </>
            ) : (
              <>
                <TooltipWrapper
                  content={`${totalTokens.toLocaleString()} tokens`}
                >
                  <span className="comet-title-m text-foreground">
                    {formatNumberInK(totalTokens)}
                  </span>
                </TooltipWrapper>
                <span className="comet-body-s text-muted-slate">tokens</span>
              </>
            )}
            {breakdown?.item_unit && (
              <>
                <span className="comet-body-s text-light-slate">·</span>
                <TooltipWrapper content={COUNT_TOOLTIP}>
                  <span className="comet-body-s text-muted-slate">
                    {formatCount(
                      breakdown.item_count ?? 0,
                      breakdown.item_unit,
                    )}
                  </span>
                </TooltipWrapper>
              </>
            )}
          </div>
          {meta.description && (
            <p className="comet-body-xs text-muted-slate">{meta.description}</p>
          )}
        </div>

        <div className="flex flex-col gap-1.5 rounded-md border bg-background px-3 py-2">
          <span className="comet-body-xs-accented text-muted-slate">
            {breakdown?.items_title ?? "Cost breakdown"}
          </span>
          {renderBreakdown()}
        </div>

        {(breakdown?.sections ?? []).map((section) => (
          <div
            key={section.title}
            className="flex flex-col gap-1.5 rounded-md border bg-background px-3 py-2"
          >
            <span className="comet-body-xs-accented text-muted-slate">
              {section.title}
            </span>
            {renderItems(section.items, section.item_unit)}
          </div>
        ))}

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
                  estSavingUsd={priceSavings(rec.estimated_savings_tokens)}
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
