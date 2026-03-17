import React, { useMemo, useRef, useState } from "react";
import {
  XAxis,
  CartesianGrid,
  YAxis,
  ComposedChart,
  Scatter,
  Customized,
} from "recharts";

import { ChartContainer } from "@/components/ui/chart";
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
  buildParentChildEdges,
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

const GHOST_ID = "__ghost__";

type OptimizationProgressChartContentProps = {
  chartData: CandidateDataPoint[];
  candidates: AggregatedCandidate[];
  bestCandidateId?: string;
  objectiveName: string;
  selectedTrialId?: string;
  onTrialSelect?: (trialId: string) => void;
  onTrialClick?: (candidateId: string) => void;
  isEvaluationSuite?: boolean;
  isInProgress?: boolean;
  inProgressInfo?: InProgressInfo;
};

const CHART_CONFIG = {
  score: { label: "Score", color: "var(--color-blue)" },
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
  isEvaluationSuite,
  isInProgress = false,
  inProgressInfo,
}) => {
  const steps = useMemo(() => {
    const s = new Set(chartData.map((d) => d.stepIndex));
    return Array.from(s).sort((a, b) => a - b);
  }, [chartData]);

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

  const {
    width: tickWidth,
    ticks,
    domain,
    yTickFormatter,
  } = useChartTickDefaultConfig(values, {
    maxTickPrecision: 2,
    targetTickCount: 3,
    showMinMaxDomain: true,
  });

  const candidateMap = useMemo(() => {
    const map = new Map<string, AggregatedCandidate>();
    for (const c of candidates) {
      map.set(c.candidateId, c);
    }
    return map;
  }, [candidates]);

  const edges = useMemo(() => buildParentChildEdges(chartData), [chartData]);

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
    pulsingCandidateId,
    selectedTrialId,
    onTrialSelect,
    onTrialClick,
    isEvaluationSuite,
    setHoveredTrial,
  });

  const renderEdges = useChartEdges({ dotPositionsRef, edges });

  const renderGhostCandidate = useGhostCandidate({
    dotPositionsRef,
    ghostStep,
    ghostXOffset,
    inProgressInfo,
    chartData,
    onTrialSelect,
    onTrialClick,
  });

  const xDomain = useMemo(() => {
    if (steps.length === 0) return [0, 1];
    const maxDataStep = steps[steps.length - 1];
    const max =
      ghostStep != null ? Math.max(maxDataStep, ghostStep) : maxDataStep;
    return [0, max + X_DOMAIN_EXTRA];
  }, [steps, ghostStep]);

  return (
    <div ref={containerRef} className="relative">
      <ChartContainer config={CHART_CONFIG} className="h-48 w-full">
        <ComposedChart data={positionedData} margin={CHART_MARGIN}>
          <CartesianGrid vertical={false} {...DEFAULT_CHART_GRID_PROPS} />
          <XAxis
            dataKey="x"
            type="number"
            axisLine={false}
            tickLine={false}
            tick={DEFAULT_CHART_TICK}
            ticks={
              ghostStep != null && !steps.includes(ghostStep)
                ? [...steps, ghostStep]
                : steps
            }
            tickFormatter={(value) => `Step ${value}`}
            domain={xDomain}
            padding={X_AXIS_PADDING}
          />
          <YAxis
            width={tickWidth}
            axisLine={false}
            tickLine={false}
            tick={DEFAULT_CHART_TICK}
            ticks={ticks}
            tickFormatter={yTickFormatter}
            domain={domain}
          />

          {/* Scatter renders BEFORE Customized so dot positions are
              captured in the ref before renderEdges reads them. */}
          <Scatter
            name={objectiveName}
            dataKey="value"
            shape={renderScatterDot as never}
            isAnimationActive={false}
          />

          {/* Edges render on top of dots in SVG paint order, but they are
              thin translucent lines so the dots remain clearly visible. */}
          <Customized component={renderEdges} />

          {/* Ghost candidate with animated connector during optimization */}
          {isInProgress && <Customized component={renderGhostCandidate} />}
        </ComposedChart>
      </ChartContainer>

      {hoveredTrial != null &&
        containerRef.current != null &&
        (() => {
          const c = candidateMap.get(hoveredTrial.candidateId);
          if (!c) return null;
          return (
            <ChartTooltip
              hoveredTrial={hoveredTrial}
              candidate={c}
              chartData={chartData}
              isEvaluationSuite={isEvaluationSuite}
            />
          );
        })()}

      <div className="mt-1 flex items-center justify-center gap-4">
        {isEvaluationSuite ? (
          TRIAL_STATUS_ORDER.filter((s) =>
            chartData.some((d) => d.status === s),
          ).map((s) => (
            <div key={s} className="flex items-center gap-1.5">
              <span
                className="size-2.5 rounded-full"
                style={{ backgroundColor: TRIAL_STATUS_COLORS[s] }}
              />
              <span className="comet-body-xs text-muted-slate">
                {TRIAL_STATUS_LABELS[s]}
              </span>
            </div>
          ))
        ) : (
          <div className="flex items-center gap-1.5">
            <span
              className="size-2.5 rounded-full"
              style={{ backgroundColor: TRIAL_STATUS_COLORS.passed }}
            />
            <span className="comet-body-xs text-muted-slate">
              {objectiveName}
            </span>
          </div>
        )}
      </div>
    </div>
  );
};

export default OptimizationProgressChartContent;
