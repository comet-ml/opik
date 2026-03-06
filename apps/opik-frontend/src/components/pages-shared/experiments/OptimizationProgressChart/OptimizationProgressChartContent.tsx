import React, { useMemo, useCallback, useRef, useState } from "react";
import { createPortal } from "react-dom";
import {
  Dot,
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
import {
  formatAsPercentage,
  formatAsDuration,
  formatAsCurrency,
} from "@/lib/optimization-formatters";
import {
  TRIAL_STATUS_COLORS,
  CandidateDataPoint,
  buildParentChildEdges,
} from "./optimizationChartUtils";

type OptimizationProgressChartContentProps = {
  chartData: CandidateDataPoint[];
  candidates: AggregatedCandidate[];
  bestCandidateId?: string;
  objectiveName: string;
  selectedTrialId?: string;
  onTrialSelect?: (trialId: string) => void;
  onTrialClick?: (candidateId: string) => void;
  isEvaluationSuite?: boolean;
};

const CHART_CONFIG = {
  score: { label: "Score", color: "var(--color-blue)" },
};

type DotPosition = { cx: number; cy: number };

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

  // Track pixel offsets for dots that share the same (step, score)
  const overlapOffsets = useMemo(() => {
    const groups = new Map<string, string[]>();
    for (const d of chartData) {
      const key = `${d.stepIndex}:${d.value}`;
      const list = groups.get(key) ?? [];
      list.push(d.candidateId);
      groups.set(key, list);
    }
    const offsets = new Map<string, number>();
    for (const ids of groups.values()) {
      if (ids.length <= 1) continue;
      const spacing = 16;
      const totalWidth = (ids.length - 1) * spacing;
      ids.forEach((id, i) => {
        offsets.set(id, -totalWidth / 2 + i * spacing);
      });
    }
    return offsets;
  }, [chartData]);

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

  // Ref to collect dot pixel positions during Scatter rendering.
  // Scatter renders before Customized (JSX order), so positions are
  // available when renderEdges executes in the same render pass.
  const containerRef = useRef<HTMLDivElement>(null);
  const dotPositionsRef = useRef<Map<string, DotPosition>>(new Map());

  const [hoveredTrial, setHoveredTrial] = useState<{
    candidateId: string;
    cx: number;
    cy: number;
  } | null>(null);

  const renderScatterDot = useCallback(
    (props: {
      cx: number;
      cy: number;
      payload: (typeof positionedData)[0];
      [key: string]: unknown;
    }) => {
      const { cx: rawCx, cy, payload } = props;
      const pxOffset = overlapOffsets.get(payload.candidateId) ?? 0;
      const cx = rawCx + pxOffset;
      const color = TRIAL_STATUS_COLORS[payload.status];
      const isBest = payload.candidateId === bestCandidateId;
      const isSelected = payload.candidateId === selectedTrialId;
      const radius = isBest ? 8 : 6;

      const handleClick = () => {
        if (onTrialClick) {
          onTrialClick(payload.candidateId);
        } else {
          onTrialSelect?.(payload.candidateId);
        }
      };

      dotPositionsRef.current.set(payload.candidateId, { cx, cy });

      return (
        <g
          key={payload.candidateId}
          onClick={handleClick}
          onMouseEnter={() =>
            setHoveredTrial({ candidateId: payload.candidateId, cx, cy })
          }
          onMouseLeave={() => setHoveredTrial(null)}
          style={{ cursor: "pointer" }}
        >
          {isSelected && (
            <Dot
              cx={cx}
              cy={cy}
              fill="none"
              stroke={color}
              strokeWidth={2}
              r={radius + 4}
              strokeOpacity={0.4}
            />
          )}
          <Dot
            cx={cx}
            cy={cy}
            fill={color}
            strokeWidth={1.5}
            stroke="white"
            r={radius}
          />
          {isBest && (
            <>
              <rect
                x={cx - 46}
                y={cy - radius - 22}
                width={92}
                height={18}
                rx={4}
                fill="hsl(var(--foreground))"
                opacity={0.85}
              />
              <text
                x={cx}
                y={cy - radius - 10}
                textAnchor="middle"
                fontSize={11}
                fill="hsl(var(--background))"
                fontWeight={600}
              >
                Best candidate
              </text>
            </>
          )}
        </g>
      );
    },
    [
      bestCandidateId,
      selectedTrialId,
      onTrialSelect,
      onTrialClick,
      overlapOffsets,
    ],
  );

  const renderEdges = useCallback(() => {
    const positions = dotPositionsRef.current;
    if (positions.size === 0) return null;

    return (
      <g>
        {edges.map((edge) => {
          const parentPos = positions.get(edge.parentCandidateId);
          const childPos = positions.get(edge.childCandidateId);
          if (!parentPos || !childPos) return null;

          const midX = (parentPos.cx + childPos.cx) / 2;
          const d = `M ${parentPos.cx},${parentPos.cy} C ${midX},${parentPos.cy} ${midX},${childPos.cy} ${childPos.cx},${childPos.cy}`;

          return (
            <path
              key={`${edge.parentCandidateId}-${edge.childCandidateId}`}
              d={d}
              fill="none"
              stroke="hsl(var(--muted-foreground))"
              strokeWidth={1}
              strokeOpacity={0.4}
            />
          );
        })}
      </g>
    );
  }, [edges]);

  const xDomain = useMemo(() => {
    if (steps.length === 0) return [0, 1];
    const max = steps[steps.length - 1];
    return [0, max + 0.3];
  }, [steps]);

  return (
    <div ref={containerRef} className="relative">
      <ChartContainer config={CHART_CONFIG} className="h-48 w-full">
        <ComposedChart
          data={positionedData}
          margin={{ top: 30, bottom: 10, left: 10, right: 10 }}
        >
          <CartesianGrid vertical={false} {...DEFAULT_CHART_GRID_PROPS} />
          <XAxis
            dataKey="x"
            type="number"
            axisLine={false}
            tickLine={false}
            tick={DEFAULT_CHART_TICK}
            ticks={steps}
            tickFormatter={(value) => `Step ${value}`}
            domain={xDomain}
            padding={{ left: 20 }}
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
        </ComposedChart>
      </ChartContainer>

      {hoveredTrial &&
        containerRef.current &&
        (() => {
          const c = candidateMap.get(hoveredTrial.candidateId);
          if (!c) return null;
          const chartPoint = chartData.find(
            (d) => d.candidateId === hoveredTrial.candidateId,
          );
          const status = chartPoint?.status ?? "passed";
          const scoreLabel = isEvaluationSuite ? "Pass rate" : "Score";
          const percentageDisplay =
            c.score != null ? formatAsPercentage(c.score) : "-";
          const fractionDisplay =
            isEvaluationSuite && c.score != null && c.totalDatasetItemCount > 0
              ? ` (${Math.round(c.score * c.totalDatasetItemCount)}/${
                  c.totalDatasetItemCount
                })`
              : "";

          const rect = containerRef.current!.getBoundingClientRect();
          const fixedLeft = rect.left + hoveredTrial.cx;
          const fixedTop = rect.top + hoveredTrial.cy - 12;

          return createPortal(
            <div
              className="pointer-events-none rounded-md border border-border px-3 py-2 shadow-md"
              style={{
                position: "fixed",
                left: fixedLeft,
                top: fixedTop,
                transform: "translate(-50%, -100%)",
                zIndex: 9999,
                backgroundColor: "hsl(var(--background))",
              }}
            >
              <div className="comet-body-xs-accented mb-1">
                Trial #{c.trialNumber}
              </div>
              <div className="flex flex-col gap-0.5">
                <div className="comet-body-xs text-muted-slate">
                  Status:{" "}
                  <span className="capitalize text-foreground">{status}</span>
                </div>
                <div className="comet-body-xs text-muted-slate">
                  {scoreLabel}:{" "}
                  <span className="text-foreground">
                    {percentageDisplay}
                    {fractionDisplay}
                  </span>
                </div>
                {c.latencyP50 != null && (
                  <div className="comet-body-xs text-muted-slate">
                    Latency:{" "}
                    <span className="text-foreground">
                      {formatAsDuration(c.latencyP50)}
                    </span>
                  </div>
                )}
                {c.runtimeCost != null && (
                  <div className="comet-body-xs text-muted-slate">
                    Avg. runtime cost:{" "}
                    <span className="text-foreground">
                      {formatAsCurrency(c.runtimeCost)}
                    </span>
                  </div>
                )}
              </div>
            </div>,
            document.body,
          );
        })()}

      <div className="mt-1 flex items-center justify-center gap-4">
        <div className="flex items-center gap-1.5">
          <span
            className="size-2.5 rounded-full"
            style={{ backgroundColor: TRIAL_STATUS_COLORS.baseline }}
          />
          <span className="comet-body-xs text-muted-slate">Baseline</span>
        </div>
        <div className="flex items-center gap-1.5">
          <span
            className="size-2.5 rounded-full"
            style={{ backgroundColor: TRIAL_STATUS_COLORS.passed }}
          />
          <span className="comet-body-xs text-muted-slate">Passed</span>
        </div>
        {chartData.some((d) => d.status === "pruned") && (
          <div className="flex items-center gap-1.5">
            <span
              className="size-2.5 rounded-full"
              style={{ backgroundColor: TRIAL_STATUS_COLORS.pruned }}
            />
            <span className="comet-body-xs text-muted-slate">Pruned</span>
          </div>
        )}
      </div>
    </div>
  );
};

export default OptimizationProgressChartContent;
