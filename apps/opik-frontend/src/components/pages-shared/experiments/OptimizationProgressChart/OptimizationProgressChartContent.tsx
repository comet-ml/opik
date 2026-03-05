import React, { useMemo, useCallback, useRef } from "react";
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
import {
  TRIAL_STATUS_COLORS,
  CandidateDataPoint,
  buildParentChildEdges,
} from "./optimizationChartUtils";

type OptimizationProgressChartContentProps = {
  chartData: CandidateDataPoint[];
  bestCandidateId?: string;
  objectiveName: string;
  selectedTrialId?: string;
  onTrialSelect?: (trialId: string) => void;
};

const CHART_CONFIG = {
  score: { label: "Score", color: "var(--color-blue)" },
};

type DotPosition = { cx: number; cy: number };

const OptimizationProgressChartContent: React.FC<
  OptimizationProgressChartContentProps
> = ({
  chartData,
  bestCandidateId,
  objectiveName,
  selectedTrialId,
  onTrialSelect,
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

  const edges = useMemo(() => buildParentChildEdges(chartData), [chartData]);

  // Ref to collect dot pixel positions during Scatter rendering.
  // Scatter renders before Customized (JSX order), so positions are
  // available when renderEdges executes in the same render pass.
  const dotPositionsRef = useRef<Map<string, DotPosition>>(new Map());

  const renderScatterDot = useCallback(
    (props: {
      cx: number;
      cy: number;
      payload: (typeof positionedData)[0];
      [key: string]: unknown;
    }) => {
      const { cx, cy, payload } = props;
      const color = TRIAL_STATUS_COLORS[payload.status];
      const isBest = payload.candidateId === bestCandidateId;
      const isSelected = payload.candidateId === selectedTrialId;
      const radius = isBest ? 8 : 6;

      const handleClick = () => onTrialSelect?.(payload.candidateId);

      dotPositionsRef.current.set(payload.candidateId, { cx, cy });

      return (
        <g
          key={payload.candidateId}
          onClick={handleClick}
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
    [bestCandidateId, selectedTrialId, onTrialSelect],
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
    <div className="relative">
      <ChartContainer config={CHART_CONFIG} className="h-48 w-full">
        <ComposedChart
          data={positionedData}
          margin={{ top: 20, bottom: 10, left: 10, right: 10 }}
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
        <div className="flex items-center gap-1.5">
          <span
            className="size-2.5 rounded-full"
            style={{ backgroundColor: TRIAL_STATUS_COLORS.lost }}
          />
          <span className="comet-body-xs text-muted-slate">Lost</span>
        </div>
      </div>
    </div>
  );
};

export default OptimizationProgressChartContent;
