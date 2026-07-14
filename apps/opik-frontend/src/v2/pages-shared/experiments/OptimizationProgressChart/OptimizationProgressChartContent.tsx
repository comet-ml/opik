import React, { useMemo, useRef, useState } from "react";
import {
  XAxis,
  CartesianGrid,
  YAxis,
  ComposedChart,
  Scatter,
  Customized,
} from "recharts";

import { ChartContainer } from "@/ui/chart";
import {
  DEFAULT_CHART_GRID_PROPS,
  DEFAULT_CHART_TICK,
} from "@/constants/chart";
import useChartTickDefaultConfig from "@/hooks/charts/useChartTickDefaultConfig";
import { AggregatedCandidate } from "@/types/optimizations";
import ChartTooltip from "./ChartTooltip";
import {
  TRIAL_STATUS_COLORS,
  TRIAL_STATUS_LABELS,
  TRIAL_STATUS_ORDER,
  CandidateDataPoint,
  buildTrendLineEdges,
  getUniqueSteps,
} from "./optimizationChartUtils";
import type { InProgressInfo } from "./optimizationChartUtils";
import {
  OVERLAP_SPACING,
  CHART_MARGIN,
  X_AXIS_PADDING,
  X_DOMAIN_EXTRA,
} from "./chartConstants";
import useScatterDot from "./ScatterDot";
import type { DotPosition } from "./ScatterDot";
import useChartEdges from "./ChartEdges";
import useGhostCandidate from "./GhostCandidate";
import useBestTrialCard from "./BestTrialCard";

const GHOST_ID = "__ghost__";

type OptimizationProgressChartContentProps = {
  chartData: CandidateDataPoint[];
  candidates: AggregatedCandidate[];
  bestCandidateId?: string;
  objectiveName: string;
  selectedTrialId?: string;
  onTrialSelect?: (trialId: string) => void;
  onTrialClick?: (candidateId: string) => void;
  isTestSuite?: boolean;
  isInProgress?: boolean;
  inProgressInfo?: InProgressInfo;
};

const CHART_CONFIG = {
  score: { label: "Score", color: "var(--color-fuchsia)" },
};

// X/Y axis tick labels per the Figma spec: Inter 10px / weight 300 / 12px
// line-height in the foreground colour (#373D4D), overriding the shared tick's
// lighter stroke + 200 weight.
const AXIS_TICK = {
  ...DEFAULT_CHART_TICK,
  stroke: "none",
  fill: "hsl(var(--foreground))",
  fontWeight: 300,
};

const OptimizationProgressChartContent: React.FC<
  OptimizationProgressChartContentProps
> = ({
  chartData,
  candidates,
  bestCandidateId,
  objectiveName,
  selectedTrialId,
  onTrialSelect,
  onTrialClick,
  isTestSuite,
  isInProgress = false,
  inProgressInfo,
}) => {
  const steps = useMemo(() => getUniqueSteps(chartData), [chartData]);

  // Test-suite runs distinguish every status (only those actually present);
  // dataset runs collapse to a fixed Passed/Discarded pair (matching the dot
  // colours from getTrialDotColor).
  const legendItems = useMemo<{ color: string; label: string }[]>(() => {
    if (isTestSuite) {
      return TRIAL_STATUS_ORDER.filter((s) =>
        chartData.some((d) => d.status === s),
      ).map((s) => ({
        color: TRIAL_STATUS_COLORS[s],
        label: TRIAL_STATUS_LABELS[s],
      }));
    }
    return [
      { color: TRIAL_STATUS_COLORS.passed, label: "Passed trial" },
      { color: TRIAL_STATUS_COLORS.pruned, label: "Discarded trial" },
    ];
  }, [isTestSuite, chartData]);

  const positionedData = useMemo(() => {
    return chartData.map((d) => ({
      ...d,
      x: d.stepIndex,
    }));
  }, [chartData]);

  const ghostStep = useMemo(() => {
    if (!isInProgress || steps.length === 0 || !inProgressInfo) return null;
    return inProgressInfo.stepIndex;
  }, [isInProgress, steps, inProgressInfo]);

  const { overlapOffsets, ghostXOffset } = useMemo(() => {
    const groups = new Map<string, string[]>();
    for (const d of chartData) {
      const key = `${d.stepIndex}:${d.value}`;
      const list = groups.get(key) ?? [];
      list.push(d.candidateId);
      groups.set(key, list);
    }

    // Include ghost in overlap groups so it spreads evenly with siblings
    if (ghostStep != null && inProgressInfo) {
      // Ghost has null value — group it with the parent's value at ghost step
      // Find the parent's score to determine which group the ghost joins
      const parentData = chartData.find((d) =>
        inProgressInfo.parentCandidateIds.includes(d.candidateId),
      );
      const ghostValue = parentData?.value ?? null;
      const ghostKey = `${ghostStep}:${ghostValue}`;
      const list = groups.get(ghostKey) ?? [];
      list.push(GHOST_ID);
      groups.set(ghostKey, list);
    }

    const offsets = new Map<string, number>();
    for (const ids of groups.values()) {
      if (ids.length <= 1) continue;
      const totalWidth = (ids.length - 1) * OVERLAP_SPACING;
      ids.forEach((id, i) => {
        offsets.set(id, -totalWidth / 2 + i * OVERLAP_SPACING);
      });
    }

    return {
      overlapOffsets: offsets,
      ghostXOffset: offsets.get(GHOST_ID) ?? 0,
    };
  }, [chartData, ghostStep, inProgressInfo]);

  const values = useMemo(
    () => positionedData.map((d) => d.value),
    [positionedData],
  );

  const { width: tickWidth } = useChartTickDefaultConfig(values, {
    maxTickPrecision: 2,
    targetTickCount: 5,
    showMinMaxDomain: true,
  });
  // Scores / pass rates are always 0–1, so pin the axis to a fixed 0–1 domain
  // with quarter ticks rather than a data-driven range.
  const yTicks = [0, 0.25, 0.5, 0.75, 1];

  const candidateMap = useMemo(() => {
    const map = new Map<string, AggregatedCandidate>();
    for (const c of candidates) {
      map.set(c.candidateId, c);
    }
    return map;
  }, [candidates]);

  const edges = useMemo(() => buildTrendLineEdges(chartData), [chartData]);

  const containerRef = useRef<HTMLDivElement>(null);
  const dotPositionsRef = useRef<Map<string, DotPosition>>(new Map());

  const [hoveredTrial, setHoveredTrial] = useState<{
    candidateId: string;
    cx: number;
    cy: number;
  } | null>(null);

  const pulsingCandidateId = useMemo(() => {
    if (!isInProgress || inProgressInfo) return undefined;
    // Find the last "passed" candidate at the highest step
    const passed = chartData
      .filter((d) => d.status === "passed")
      .sort((a, b) => b.stepIndex - a.stepIndex || b.value! - a.value! || 0);
    return passed[0]?.candidateId;
  }, [isInProgress, inProgressInfo, chartData]);

  const renderScatterDot = useScatterDot({
    dotPositionsRef,
    overlapOffsets,
    bestCandidateId,
    hoveredCandidateId: hoveredTrial?.candidateId,
    pulsingCandidateId,
    selectedTrialId,
    onTrialSelect,
    onTrialClick,
    isTestSuite,
    setHoveredTrial,
  });

  const renderEdges = useChartEdges({ edges, chartData, overlapOffsets });

  const renderGhostCandidate = useGhostCandidate({
    dotPositionsRef,
    ghostStep,
    ghostXOffset,
    inProgressInfo,
    chartData,
    onTrialSelect,
    onTrialClick,
  });

  const renderBestCard = useBestTrialCard({
    dotPositionsRef,
    containerRef,
    bestCandidateId,
    hoveredCandidateId: hoveredTrial?.candidateId,
    candidateMap,
    chartData,
    isTestSuite,
  });

  const xDomain = useMemo(() => {
    if (steps.length === 0) return [0, 1];
    const maxDataStep = steps[steps.length - 1];
    const max =
      ghostStep != null ? Math.max(maxDataStep, ghostStep) : maxDataStep;
    return [0, max + X_DOMAIN_EXTRA];
  }, [steps, ghostStep]);

  // The hover tooltip only serves non-best dots — the best dot's card is the
  // always-visible pinned one (useBestTrialCard).
  const hoveredCandidate =
    hoveredTrial != null && hoveredTrial.candidateId !== bestCandidateId
      ? candidateMap.get(hoveredTrial.candidateId)
      : undefined;

  return (
    <div ref={containerRef} className="relative">
      <ChartContainer config={CHART_CONFIG} className="h-40 w-full">
        <ComposedChart data={positionedData} margin={CHART_MARGIN}>
          <CartesianGrid
            vertical={false}
            {...DEFAULT_CHART_GRID_PROPS}
            stroke="#E2E8F0"
          />
          <XAxis
            dataKey="x"
            type="number"
            height={24}
            axisLine={false}
            tickLine={false}
            tick={AXIS_TICK}
            ticks={
              ghostStep != null && !steps.includes(ghostStep)
                ? [...steps, ghostStep]
                : steps
            }
            tickFormatter={(value) =>
              value === 0 ? "Baseline" : `Step ${value}`
            }
            domain={xDomain}
            padding={X_AXIS_PADDING}
          />
          <YAxis
            width={tickWidth}
            axisLine={false}
            tickLine={false}
            tick={AXIS_TICK}
            ticks={yTicks}
            interval={0}
            tickFormatter={(value) => Number(value).toFixed(2)}
            domain={[0, 1]}
          />

          {/* Edges render BEFORE the Scatter so the trend line sits underneath
              the dots. Positions come from the chart scales, so the line does
              not depend on the Scatter having drawn first. */}
          <Customized component={renderEdges} />

          <Scatter
            name={objectiveName}
            dataKey="value"
            shape={renderScatterDot as never}
            isAnimationActive={false}
          />

          {/* Ghost candidate with animated connector during optimization */}
          {isInProgress && <Customized component={renderGhostCandidate} />}

          {/* Pinned best-trial card, anchored below the best dot. */}
          <Customized component={renderBestCard} />
        </ComposedChart>
      </ChartContainer>

      {hoveredTrial != null && hoveredCandidate != null && (
        <ChartTooltip
          hoveredTrial={hoveredTrial}
          candidate={hoveredCandidate}
          chartData={chartData}
          isTestSuite={isTestSuite}
          bestCandidateId={bestCandidateId}
        />
      )}

      <div className="mt-1 flex items-center justify-center">
        {legendItems.map(({ color, label }) => (
          <div key={label} className="flex items-center gap-0.5 pl-1 pr-1.5">
            <span className="flex size-3 items-center justify-center">
              <span
                className="size-1.5 rounded-full"
                style={{ backgroundColor: color }}
              />
            </span>
            <span className="comet-body-xs text-foreground">{label}</span>
          </div>
        ))}
      </div>
    </div>
  );
};

export default OptimizationProgressChartContent;
