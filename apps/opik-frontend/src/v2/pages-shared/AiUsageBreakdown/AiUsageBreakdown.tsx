import React, { useLayoutEffect, useMemo, useRef, useState } from "react";
import { Coins } from "lucide-react";
import useAiSpendComposition from "@/api/ai-spend/useAiSpendComposition";
import useAiSpendRecommendations from "@/api/ai-spend/useAiSpendRecommendations";
import { Card } from "@/ui/card";
import { Skeleton } from "@/ui/skeleton";
import { cn } from "@/lib/utils";
import { formatCost } from "@/lib/money";
import AnthropicIcon from "@/icons/integrations/anthropic.svg?react";
import BreakdownColumn from "./BreakdownColumn";
import SankeyLinks, { RibbonPath } from "./SankeyLinks";
import { getLaneMeta } from "./laneRegistry";
import { ribbonWidth, sideCostTotal, toLaneViews } from "./utils";
import { LaneSide, LaneView } from "./types";

export interface AiUsageBreakdownProps {
  projectName: string;
  intervalStart: string;
  intervalEnd: string;
  userUuid?: string;
  compact?: boolean;
  className?: string;
  onLaneClick?: (laneKey: string) => void;
  onLaneHover?: (laneKey: string | null) => void;
  activeLaneKey?: string | null;
  highlightedLaneKey?: string | null;
  renderLaneWrapper?: (
    lane: LaneView,
    card: React.ReactNode,
    side: LaneSide,
  ) => React.ReactNode;
}

interface PathsState {
  left: RibbonPath[];
  right: RibbonPath[];
  size: { w: number; h: number };
}

const EMPTY_PATHS: PathsState = { left: [], right: [], size: { w: 0, h: 0 } };

const HARNESS_ICONS: Record<string, React.FC<React.SVGProps<SVGSVGElement>>> = {
  claude_code: AnthropicIcon,
};

const AiUsageBreakdown: React.FC<AiUsageBreakdownProps> = ({
  projectName,
  intervalStart,
  intervalEnd,
  userUuid,
  compact,
  className,
  onLaneClick,
  onLaneHover,
  activeLaneKey,
  highlightedLaneKey,
  renderLaneWrapper,
}) => {
  const { data, isPending, isError } = useAiSpendComposition({
    projectName,
    intervalStart,
    intervalEnd,
    userUuid,
  });

  const [hoveredLaneKey, setHoveredLaneKey] = useState<string | null>(null);
  const [paths, setPaths] = useState<PathsState>(EMPTY_PATHS);

  const containerRef = useRef<HTMLDivElement>(null);
  const harnessBoxRef = useRef<HTMLDivElement>(null);
  const leftRefs = useRef<(HTMLDivElement | null)[]>([]);
  const rightRefs = useRef<(HTMLDivElement | null)[]>([]);

  const { data: recommendationsData } = useAiSpendRecommendations({
    projectName,
    intervalStart,
    intervalEnd,
    userUuid,
  });
  const recommendationLaneKeys = useMemo(
    () =>
      new Set(
        (recommendationsData?.items ?? [])
          .map((item) => item.related_lane_key)
          .filter((key): key is string => Boolean(key)),
      ),
    [recommendationsData],
  );

  const inputLanes = useMemo(() => toLaneViews(data?.input), [data]);
  const outputLanes = useMemo(() => toLaneViews(data?.output), [data]);
  const harness = data?.harness ?? [];
  // FE-priced from the lanes' tier columns; the harness card shows the
  // window's whole bill (input side + output side).
  const harnessTotal =
    (sideCostTotal(inputLanes) ?? 0) + (sideCostTotal(outputLanes) ?? 0);

  const columnWidth = compact ? "w-52" : "w-60";

  const handleHover = (laneKey: string | null) => {
    setHoveredLaneKey(laneKey);
    onLaneHover?.(laneKey);
  };

  const emphasizedLaneKey = hoveredLaneKey ?? highlightedLaneKey ?? null;

  const recompute = () => {
    const container = containerRef.current;
    const box = harnessBoxRef.current;
    if (!container || !box) {
      return;
    }

    const cRect = container.getBoundingClientRect();
    const bRect = box.getBoundingClientRect();
    const stackLeftX = bRect.left - cRect.left;
    const stackRightX = bRect.right - cRect.left;
    const stackTopY = bRect.top - cRect.top;
    const stackBotY = bRect.bottom - cRect.top;
    const stackHeight = stackBotY - stackTopY;
    const pad = Math.min(stackHeight * 0.8, 12);
    const usableTop = stackTopY + pad;
    const usableBot = stackBotY - pad;
    const fanY = (i: number, n: number) =>
      n <= 1
        ? (usableTop + usableBot) / 2
        : usableTop + (i / (n - 1)) * (usableBot - usableTop);

    const maxIn = Math.max(...inputLanes.map((l) => l.weight), 1);
    const maxOut = Math.max(...outputLanes.map((l) => l.weight), 1);

    const left = inputLanes
      .map((lane, i, arr): RibbonPath | null => {
        const el = leftRefs.current[i];
        if (!el) return null;
        const r = el.getBoundingClientRect();
        const x = r.right - cRect.left;
        const y = (r.top + r.bottom) / 2 - cRect.top;
        const yAttach = fanY(i, arr.length);
        const midX = (x + stackLeftX) / 2;
        return {
          laneKey: lane.key,
          d: `M ${x} ${y} C ${midX} ${y}, ${midX} ${yAttach}, ${stackLeftX} ${yAttach}`,
          color: getLaneMeta(lane.key, lane.label).color,
          width: ribbonWidth(lane.weight, maxIn),
        };
      })
      .filter((p): p is RibbonPath => p !== null);

    const right = outputLanes
      .map((lane, i, arr): RibbonPath | null => {
        const el = rightRefs.current[i];
        if (!el) return null;
        const r = el.getBoundingClientRect();
        const x = r.left - cRect.left;
        const y = (r.top + r.bottom) / 2 - cRect.top;
        const yAttach = fanY(i, arr.length);
        const midX = (stackRightX + x) / 2;
        return {
          laneKey: lane.key,
          d: `M ${stackRightX} ${yAttach} C ${midX} ${yAttach}, ${midX} ${y}, ${x} ${y}`,
          color: getLaneMeta(lane.key, lane.label).color,
          width: ribbonWidth(lane.weight, maxOut),
        };
      })
      .filter((p): p is RibbonPath => p !== null);

    setPaths({ left, right, size: { w: cRect.width, h: cRect.height } });
  };

  useLayoutEffect(() => {
    recompute();
    const container = containerRef.current;
    if (!container || typeof ResizeObserver === "undefined") return;
    const observer = new ResizeObserver(() => recompute());
    observer.observe(container);
    return () => observer.disconnect();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [inputLanes, outputLanes, harness.length]);

  if (isPending) {
    return (
      <Card className={cn("p-4", className)}>
        <div className="flex items-center justify-between gap-12">
          {[0, 1, 2].map((col) => (
            <div key={col} className={cn("flex flex-col gap-2", columnWidth)}>
              {[0, 1, 2].map((row) => (
                <Skeleton key={row} className="h-14 w-full" />
              ))}
            </div>
          ))}
        </div>
      </Card>
    );
  }

  if (isError || !data) {
    return (
      <Card className={cn("p-4", className)}>
        <div className="comet-body-s py-8 text-center text-muted-slate">
          Could not load AI usage breakdown.
        </div>
      </Card>
    );
  }

  const isEmpty =
    inputLanes.length === 0 &&
    outputLanes.length === 0 &&
    (data.input?.total_tokens ?? 0) === 0 &&
    (data.output?.total_tokens ?? 0) === 0 &&
    harnessTotal === 0;

  if (isEmpty) {
    return (
      <Card className={cn("p-4", className)}>
        <div className="comet-body-s py-8 text-center text-muted-slate">
          No AI usage in this period.
        </div>
      </Card>
    );
  }

  return (
    <Card className={cn("p-4", className)}>
      <div ref={containerRef} className="relative">
        <SankeyLinks
          paths={[...paths.left, ...paths.right]}
          width={paths.size.w}
          height={paths.size.h}
          highlightedKey={emphasizedLaneKey}
        />
        <div className="relative z-10 flex items-center justify-between">
          <div className={cn("shrink-0", columnWidth)}>
            <BreakdownColumn
              title="Input"
              side="input"
              totalTokens={data.input?.total_tokens ?? 0}
              totalCost={sideCostTotal(inputLanes)}
              lanes={inputLanes}
              onLaneClick={onLaneClick}
              onLaneHover={handleHover}
              activeLaneKey={emphasizedLaneKey ?? activeLaneKey}
              recommendationLaneKeys={recommendationLaneKeys}
              renderLaneWrapper={renderLaneWrapper}
              compact={compact}
              registerRef={(index, el) => {
                leftRefs.current[index] = el;
              }}
            />
          </div>

          <div className={cn("flex shrink-0 flex-col gap-2", columnWidth)}>
            <span className="comet-body-s text-foreground">Harness</span>
            <div ref={harnessBoxRef} className="flex flex-col gap-2">
              {harness.map((entry) => {
                const meta = getLaneMeta(entry.key, entry.label);
                const Icon = meta.icon;
                const Logo = HARNESS_ICONS[entry.key];
                return (
                  <div
                    key={entry.key}
                    className="flex flex-col gap-0.5 rounded-md border bg-background p-2"
                  >
                    <div className="flex items-center gap-2">
                      <div className="flex size-4 shrink-0 items-center justify-center overflow-hidden rounded-sm bg-primary-foreground text-foreground">
                        {Logo ? (
                          <Logo className="size-3" />
                        ) : (
                          <Icon className="size-2.5" />
                        )}
                      </div>
                      <span className="comet-body-xs-accented min-w-0 flex-1 truncate text-foreground">
                        {entry.label || meta.labelFallback}
                      </span>
                    </div>
                    <div className="flex items-center gap-3 py-0.5">
                      <span className="comet-body-xs flex items-center gap-1 text-muted-slate">
                        <Coins className="size-3" />
                        {formatCost(harnessTotal)}
                      </span>
                    </div>
                  </div>
                );
              })}
            </div>
          </div>

          <div className={cn("shrink-0", columnWidth)}>
            <BreakdownColumn
              title="Output"
              side="output"
              totalTokens={data.output?.total_tokens ?? 0}
              totalCost={sideCostTotal(outputLanes)}
              lanes={outputLanes}
              onLaneClick={onLaneClick}
              onLaneHover={handleHover}
              activeLaneKey={emphasizedLaneKey ?? activeLaneKey}
              recommendationLaneKeys={recommendationLaneKeys}
              renderLaneWrapper={renderLaneWrapper}
              compact={compact}
              registerRef={(index, el) => {
                rightRefs.current[index] = el;
              }}
            />
          </div>
        </div>
      </div>
    </Card>
  );
};

export default AiUsageBreakdown;
