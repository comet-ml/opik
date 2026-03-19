import React, { useCallback } from "react";

import type { ParentChildEdge } from "./optimizationChartUtils";
import type { DotPosition } from "./ScatterDot";
import {
  EDGE_STROKE_WIDTH,
  EDGE_STROKE_OPACITY,
  EDGE_STROKE_COLOR,
} from "./chartConstants";

type UseChartEdgesParams = {
  dotPositionsRef: React.MutableRefObject<Map<string, DotPosition>>;
  edges: ParentChildEdge[];
};

const useChartEdges = ({ dotPositionsRef, edges }: UseChartEdgesParams) => {
  return useCallback(() => {
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
              stroke={EDGE_STROKE_COLOR}
              strokeWidth={EDGE_STROKE_WIDTH}
              strokeOpacity={EDGE_STROKE_OPACITY}
            />
          );
        })}
      </g>
    );
  }, [edges, dotPositionsRef]);
};

export default useChartEdges;
