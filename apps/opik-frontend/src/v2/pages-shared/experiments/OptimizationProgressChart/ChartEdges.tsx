import React, { useCallback } from "react";

import type {
  ParentChildEdge,
  CandidateDataPoint,
  ChartPoint,
} from "./optimizationChartUtils";
import { buildEdgePath } from "./optimizationChartUtils";
import {
  EDGE_STROKE_WIDTH,
  EDGE_STROKE_OPACITY,
  EDGE_STROKE_COLOR,
} from "./chartConstants";

type Scale = (value: number) => number;

/** Recharts hands <Customized> its internal axis maps, keyed by axis id. */
type CustomizedAxisProps = {
  xAxisMap?: Record<string, { scale?: Scale }>;
  yAxisMap?: Record<string, { scale?: Scale }>;
};

const firstScale = (map?: Record<string, { scale?: Scale }>) =>
  map ? Object.values(map)[0]?.scale : undefined;

type UseChartEdgesParams = {
  edges: ParentChildEdge[];
  chartData: CandidateDataPoint[];
  overlapOffsets: Map<string, number>;
};

const useChartEdges = ({
  edges,
  chartData,
  overlapOffsets,
}: UseChartEdgesParams) =>
  useCallback(
    (props: CustomizedAxisProps) => {
      // Positions come from the chart scales (not the shared dot-position ref)
      // so edges can render before the Scatter — underneath the dots.
      const xScale = firstScale(props.xAxisMap);
      const yScale = firstScale(props.yAxisMap);
      if (!xScale || !yScale) return null;

      const positions = new Map<string, ChartPoint>();
      for (const d of chartData) {
        if (d.value == null) continue;
        positions.set(d.candidateId, {
          cx: xScale(d.stepIndex) + (overlapOffsets.get(d.candidateId) ?? 0),
          cy: yScale(d.value),
        });
      }
      if (positions.size === 0) return null;

      return (
        // Decorative connectors must never intercept dot hovers/clicks.
        <g pointerEvents="none">
          {edges.map((edge) => {
            const from = positions.get(edge.parentCandidateId);
            const to = positions.get(edge.childCandidateId);
            if (!from || !to) return null;

            return (
              <path
                key={`${edge.parentCandidateId}-${edge.childCandidateId}`}
                d={buildEdgePath(from, to)}
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

export default useChartEdges;
