import React, { useCallback } from "react";

import type {
  ParentChildEdge,
  CandidateDataPoint,
} from "./optimizationChartUtils";
import {
  EDGE_STROKE_WIDTH,
  EDGE_STROKE_OPACITY,
  EDGE_STROKE_COLOR,
} from "./chartConstants";

type Scale = (value: number) => number;

type CustomizedAxisProps = {
  xAxisMap?: Record<string, { scale?: Scale }>;
  yAxisMap?: Record<string, { scale?: Scale }>;
};

type UseChartEdgesParams = {
  edges: ParentChildEdge[];
  chartData: CandidateDataPoint[];
  overlapOffsets: Map<string, number>;
};

const useChartEdges = ({
  edges,
  chartData,
  overlapOffsets,
}: UseChartEdgesParams) => {
  return useCallback(
    (props: CustomizedAxisProps) => {
      // Positions are derived from the chart scales (not the dot-position ref)
      // so the edges can render BEFORE the Scatter — i.e. underneath the dots,
      // matching Figma — without depending on the Scatter having drawn first.
      const xScale = props.xAxisMap
        ? Object.values(props.xAxisMap)[0]?.scale
        : undefined;
      const yScale = props.yAxisMap
        ? Object.values(props.yAxisMap)[0]?.scale
        : undefined;
      if (!xScale || !yScale) return null;

      const positions = new Map<string, { cx: number; cy: number }>();
      for (const d of chartData) {
        if (d.value == null) continue;
        positions.set(d.candidateId, {
          cx: xScale(d.stepIndex) + (overlapOffsets.get(d.candidateId) ?? 0),
          cy: yScale(d.value),
        });
      }
      if (positions.size === 0) return null;

      return (
        // Decorative connector lines must never intercept dot hovers/clicks.
        <g pointerEvents="none">
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
                stroke={EDGE_STROKE_COLOR}
                strokeWidth={EDGE_STROKE_WIDTH}
                strokeOpacity={EDGE_STROKE_OPACITY}
              />
            );
          })}
        </g>
      );
    },
    [edges, chartData, overlapOffsets],
  );
};

export default useChartEdges;
