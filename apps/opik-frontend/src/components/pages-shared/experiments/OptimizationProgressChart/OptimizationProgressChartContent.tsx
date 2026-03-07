import React, { useMemo, useCallback, useRef, useState } from "react";
import { createPortal } from "react-dom";
import isNumber from "lodash/isNumber";
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
import type { InProgressInfo } from "./optimizationChartUtils";

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

  // Ghost step: derived from parent step + 1. Always at the proposed step —
  // if another candidate already exists at that step, the chart handles overlap.
  const ghostStep = useMemo(() => {
    if (!isInProgress || steps.length === 0 || !inProgressInfo) return null;
    return inProgressInfo.stepIndex;
  }, [isInProgress, steps, inProgressInfo]);

  const xDomain = useMemo(() => {
    if (steps.length === 0) return [0, 1];
    const maxDataStep = steps[steps.length - 1];
    const max =
      ghostStep != null ? Math.max(maxDataStep, ghostStep) : maxDataStep;
    return [0, max + 0.3];
  }, [steps, ghostStep]);

  const renderGhostCandidate = useCallback(() => {
    if (ghostStep == null || !inProgressInfo) return null;

    const positions = dotPositionsRef.current;

    // Collect all parent positions (crossover can merge 2+ parents)
    const parentPositions: DotPosition[] = [];
    for (const pid of inProgressInfo.parentCandidateIds) {
      const pos = positions.get(pid);
      if (pos) parentPositions.push(pos);
    }
    if (!parentPositions.length) return null;

    // Calculate ghost X from existing dot positions
    const sortedCandidatesByStep = chartData
      .slice()
      .sort((a, b) => a.stepIndex - b.stepIndex);
    if (sortedCandidatesByStep.length < 2) return null;

    let refA: { step: number; cx: number } | null = null;
    let refB: { step: number; cx: number } | null = null;
    for (const d of sortedCandidatesByStep) {
      const pos = positions.get(d.candidateId);
      if (!pos) continue;
      if (!refA) {
        refA = { step: d.stepIndex, cx: pos.cx };
      } else if (d.stepIndex !== refA.step) {
        refB = { step: d.stepIndex, cx: pos.cx };
        break;
      }
    }
    if (!refA || !refB) return null;

    const pxPerStep = (refB.cx - refA.cx) / (refB.step - refA.step);
    const ghostCx = refA.cx + pxPerStep * (ghostStep - refA.step);
    const ghostCy =
      parentPositions.reduce((sum, p) => sum + p.cy, 0) /
      parentPositions.length;

    return (
      <g>
        {parentPositions.map((parentPos, i) => {
          const midX = (parentPos.cx + ghostCx) / 2;
          const pathD = `M ${parentPos.cx},${parentPos.cy} C ${midX},${parentPos.cy} ${midX},${ghostCy} ${ghostCx},${ghostCy}`;
          return (
            <path
              key={i}
              d={pathD}
              fill="none"
              stroke="hsl(var(--muted-foreground))"
              strokeWidth={1.5}
              strokeDasharray="4 3"
              strokeOpacity={0.5}
            >
              <animate
                attributeName="stroke-dashoffset"
                from="14"
                to="0"
                dur="1s"
                repeatCount="indefinite"
              />
            </path>
          );
        })}
        <circle
          cx={ghostCx}
          cy={ghostCy}
          r={6}
          fill="var(--color-blue)"
          fillOpacity={0.2}
          stroke="var(--color-blue)"
          strokeWidth={1.5}
          strokeOpacity={0.4}
        >
          <animate
            attributeName="r"
            values="4;8;4"
            dur="2s"
            repeatCount="indefinite"
          />
          <animate
            attributeName="fill-opacity"
            values="0.3;0.1;0.3"
            dur="2s"
            repeatCount="indefinite"
          />
          <animate
            attributeName="stroke-opacity"
            values="0.6;0.2;0.6"
            dur="2s"
            repeatCount="indefinite"
          />
        </circle>
      </g>
    );
  }, [ghostStep, inProgressInfo, chartData]);

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
            ticks={
              ghostStep != null && !steps.includes(ghostStep)
                ? [...steps, ghostStep]
                : steps
            }
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

          {/* Ghost candidate with animated connector during optimization */}
          {isInProgress && <Customized component={renderGhostCandidate} />}
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
          const percentageDisplay = isNumber(c.score)
            ? formatAsPercentage(c.score)
            : "-";
          const fractionDisplay =
            isEvaluationSuite &&
            isNumber(c.score) &&
            c.totalDatasetItemCount > 0
              ? ` (${Math.round(c.score * c.totalDatasetItemCount)}/${
                  c.totalDatasetItemCount
                })`
              : "";

          const rows: { label: string; value: string }[] = [
            { label: "Status", value: status },
            {
              label: scoreLabel,
              value: `${percentageDisplay}${fractionDisplay}`,
            },
          ];
          if (c.latencyP50 != null) {
            rows.push({
              label: "Latency",
              value: formatAsDuration(c.latencyP50),
            });
          }
          if (c.runtimeCost != null) {
            rows.push({
              label: "Runtime cost",
              value: formatAsCurrency(c.runtimeCost),
            });
          }

          const rect = containerRef.current!.getBoundingClientRect();
          const fixedLeft = rect.left + hoveredTrial.cx;
          const fixedTop = rect.top + hoveredTrial.cy - 16;

          return createPortal(
            <div
              className="pointer-events-none min-w-32 max-w-72 rounded-md border border-border px-1 py-1.5 shadow-md"
              style={{
                position: "fixed",
                left: fixedLeft,
                top: fixedTop,
                transform: "translate(-50%, -100%)",
                zIndex: 9999,
                backgroundColor: "hsl(var(--background))",
              }}
            >
              <div className="grid items-start gap-1.5">
                <div className="mb-1 max-w-full overflow-hidden border-b px-2 pt-0.5">
                  <div className="comet-body-xs-accented mb-0.5 truncate">
                    Trial #{c.trialNumber}
                  </div>
                </div>
                <div className="grid gap-1.5">
                  {rows.map((row) => (
                    <div
                      key={row.label}
                      className="flex h-6 w-full items-center px-2"
                    >
                      <div className="flex flex-1 items-center justify-between gap-2 leading-none">
                        <span className="comet-body-xs truncate text-muted-slate">
                          {row.label}
                        </span>
                        <span className="comet-body-xs capitalize">
                          {row.value}
                        </span>
                      </div>
                    </div>
                  ))}
                </div>
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
