import React, { useCallback } from "react";

import {
  TRIAL_STATUS_COLORS,
  CandidateDataPoint,
} from "./optimizationChartUtils";
import type { InProgressInfo } from "./optimizationChartUtils";
import type { DotPosition } from "./ScatterDot";
import {
  DOT_RADIUS_DEFAULT,
  DOT_STROKE_WIDTH,
  GHOST_EDGE_STROKE_WIDTH,
  GHOST_EDGE_DASH_ARRAY,
  GHOST_EDGE_STROKE_OPACITY,
  GHOST_EDGE_ANIMATION_FROM,
  GHOST_EDGE_ANIMATION_DUR,
  GHOST_DOT_FILL_OPACITY,
  GHOST_DOT_STROKE_OPACITY,
  GHOST_BREATHE_DUR,
  EDGE_STROKE_COLOR,
  createTrialClickHandler,
} from "./chartConstants";

type UseGhostCandidateParams = {
  dotPositionsRef: React.MutableRefObject<Map<string, DotPosition>>;
  ghostStep: number | null;
  ghostXOffset: number;
  inProgressInfo?: InProgressInfo;
  chartData: CandidateDataPoint[];
  onTrialSelect?: (trialId: string) => void;
  onTrialClick?: (candidateId: string) => void;
};

const useGhostCandidate = ({
  dotPositionsRef,
  ghostStep,
  ghostXOffset,
  inProgressInfo,
  chartData,
  onTrialSelect,
  onTrialClick,
}: UseGhostCandidateParams) => {
  return useCallback(() => {
    if (ghostStep == null || !inProgressInfo) return null;

    const positions = dotPositionsRef.current;

    const parentPositions: DotPosition[] = [];
    for (const pid of inProgressInfo.parentCandidateIds) {
      const pos = positions.get(pid);
      if (pos) parentPositions.push(pos);
    }
    if (!parentPositions.length) return null;

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
    const ghostCx =
      refA.cx + pxPerStep * (ghostStep - refA.step) + ghostXOffset;
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
              stroke={EDGE_STROKE_COLOR}
              strokeWidth={GHOST_EDGE_STROKE_WIDTH}
              strokeDasharray={GHOST_EDGE_DASH_ARRAY}
              strokeOpacity={GHOST_EDGE_STROKE_OPACITY}
            >
              <animate
                attributeName="stroke-dashoffset"
                from={GHOST_EDGE_ANIMATION_FROM}
                to="0"
                dur={GHOST_EDGE_ANIMATION_DUR}
                repeatCount="indefinite"
              />
            </path>
          );
        })}
        <circle
          cx={ghostCx}
          cy={ghostCy}
          r={DOT_RADIUS_DEFAULT}
          fill={TRIAL_STATUS_COLORS.running}
          fillOpacity={GHOST_DOT_FILL_OPACITY}
          stroke={TRIAL_STATUS_COLORS.running}
          strokeWidth={DOT_STROKE_WIDTH}
          strokeOpacity={GHOST_DOT_STROKE_OPACITY}
          style={{ cursor: "pointer" }}
          onClick={createTrialClickHandler(
            inProgressInfo.candidateId,
            onTrialClick,
            onTrialSelect,
          )}
        >
          <animate
            attributeName="r"
            values="4;8;4"
            dur={GHOST_BREATHE_DUR}
            repeatCount="indefinite"
          />
          <animate
            attributeName="fill-opacity"
            values="0.3;0.1;0.3"
            dur={GHOST_BREATHE_DUR}
            repeatCount="indefinite"
          />
          <animate
            attributeName="stroke-opacity"
            values="0.6;0.2;0.6"
            dur={GHOST_BREATHE_DUR}
            repeatCount="indefinite"
          />
        </circle>
      </g>
    );
  }, [
    ghostStep,
    inProgressInfo,
    chartData,
    ghostXOffset,
    onTrialClick,
    onTrialSelect,
    dotPositionsRef,
  ]);
};

export default useGhostCandidate;
