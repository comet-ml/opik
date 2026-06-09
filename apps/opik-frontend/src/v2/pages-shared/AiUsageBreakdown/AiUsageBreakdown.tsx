import React, { useLayoutEffect, useMemo, useRef, useState } from "react";
import useAiSpendComposition from "@/api/ai-spend/useAiSpendComposition";
import { Card } from "@/ui/card";
import { Skeleton } from "@/ui/skeleton";
import { cn } from "@/lib/utils";
import { formatCost } from "@/lib/money";
import BreakdownColumn from "./BreakdownColumn";
import LaneBreakdownPanel from "./LaneBreakdownPanel";
import SankeyLinks, { RibbonPath } from "./SankeyLinks";
import { getLaneMeta } from "./laneRegistry";
import { ribbonWidth, toLaneViews } from "./utils";

export interface AiUsageBreakdownProps {
  projectName: string;
  intervalStart: string;
  intervalEnd: string;
  userUuid?: string;
  compact?: boolean;
  className?: string;
}

interface PathsState {
  left: RibbonPath[];
  right: RibbonPath[];
  size: { w: number; h: number };
}

const EMPTY_PATHS: PathsState = { left: [], right: [], size: { w: 0, h: 0 } };

const AiUsageBreakdown: React.FC<AiUsageBreakdownProps> = ({
  projectName,
  intervalStart,
  intervalEnd,
  userUuid,
  compact,
  className,
}) => {
  const { data, isPending, isError } = useAiSpendComposition({
    projectName,
    intervalStart,
    intervalEnd,
    userUuid,
  });

  const [openLaneKey, setOpenLaneKey] = useState<string | null>(null);
  const [paths, setPaths] = useState<PathsState>(EMPTY_PATHS);

  const containerRef = useRef<HTMLDivElement>(null);
  const harnessBoxRef = useRef<HTMLDivElement>(null);
  const leftRefs = useRef<(HTMLDivElement | null)[]>([]);
  const rightRefs = useRef<(HTMLDivElement | null)[]>([]);

  const inputLanes = useMemo(() => toLaneViews(data?.input), [data]);
  const outputLanes = useMemo(() => toLaneViews(data?.output), [data]);
  const harness = data?.harness ?? [];

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
    const pad = Math.min(18, stackHeight * 0.12);
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

  const title = (
    <h2 className="comet-body-accented mb-4 text-foreground">
      AI usage breakdown
    </h2>
  );

  if (isPending) {
    return (
      <Card className={cn("p-4", className)}>
        {title}
        <div className="grid grid-cols-3 gap-6">
          {[0, 1, 2].map((col) => (
            <div key={col} className="flex flex-col gap-2">
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
        {title}
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
    (data.output?.total_tokens ?? 0) === 0;

  if (isEmpty) {
    return (
      <Card className={cn("p-4", className)}>
        {title}
        <div className="comet-body-s py-8 text-center text-muted-slate">
          No AI usage in this period.
        </div>
      </Card>
    );
  }

  return (
    <Card className={cn("p-4", className)}>
      {title}
      <div ref={containerRef} className="relative">
        <SankeyLinks
          paths={[...paths.left, ...paths.right]}
          width={paths.size.w}
          height={paths.size.h}
        />
        <div
          className={cn(
            "relative z-10 grid items-center gap-x-12",
            compact
              ? "grid-cols-[minmax(150px,1fr)_auto_minmax(150px,1fr)] gap-x-8"
              : "grid-cols-[minmax(200px,1fr)_auto_minmax(200px,1fr)]",
          )}
        >
          <BreakdownColumn
            title="Input"
            side="input"
            totalCost={data.input?.total_estimated_cost ?? null}
            lanes={inputLanes}
            onDrill={setOpenLaneKey}
            activeLaneKey={openLaneKey}
            compact={compact}
            registerRef={(index, el) => {
              leftRefs.current[index] = el;
            }}
          />

          <div
            ref={harnessBoxRef}
            className="flex flex-col gap-2 rounded-xl border border-dashed p-3"
          >
            <span className="comet-body-s-accented text-center text-muted-slate">
              Harness
            </span>
            {harness.map((entry) => {
              const meta = getLaneMeta(entry.key, entry.label);
              const Icon = meta.icon;
              return (
                <div
                  key={entry.key}
                  className="flex items-center gap-2 rounded-lg border bg-background p-2"
                >
                  <div
                    className="flex size-6 shrink-0 items-center justify-center rounded-md"
                    style={{
                      backgroundColor: `${meta.color}1f`,
                      color: meta.color,
                    }}
                  >
                    <Icon className="size-3.5" />
                  </div>
                  <span className="comet-body-s-accented truncate text-foreground">
                    {entry.label || meta.labelFallback}
                  </span>
                  <span className="comet-body-xs ml-auto shrink-0 text-muted-slate">
                    {formatCost(entry.total_estimated_cost)}
                  </span>
                </div>
              );
            })}
          </div>

          <BreakdownColumn
            title="Output"
            side="output"
            totalCost={data.output?.total_estimated_cost ?? null}
            lanes={outputLanes}
            onDrill={setOpenLaneKey}
            activeLaneKey={openLaneKey}
            compact={compact}
            registerRef={(index, el) => {
              rightRefs.current[index] = el;
            }}
          />
        </div>
      </div>

      <LaneBreakdownPanel
        laneKey={openLaneKey}
        projectName={projectName}
        intervalStart={intervalStart}
        intervalEnd={intervalEnd}
        userUuid={userUuid}
        onClose={() => setOpenLaneKey(null)}
      />
    </Card>
  );
};

export default AiUsageBreakdown;
