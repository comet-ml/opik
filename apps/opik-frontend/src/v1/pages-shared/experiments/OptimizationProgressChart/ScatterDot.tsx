import React, { useCallback } from "react";
import { Dot } from "recharts";

import {
  TRIAL_STATUS_COLORS,
  CandidateDataPoint,
} from "./optimizationChartUtils";
import {
  DOT_RADIUS_DEFAULT,
  DOT_RADIUS_BEST,
  SELECTION_RING_EXTRA_RADIUS,
  SELECTION_RING_STROKE_WIDTH,
  SELECTION_RING_STROKE_OPACITY,
  DOT_STROKE_WIDTH,
  DOT_STROKE_COLOR,
  BEST_LABEL_WIDTH,
  BEST_LABEL_HEIGHT,
  BEST_LABEL_BORDER_RADIUS,
  BEST_LABEL_Y_OFFSET,
  BEST_LABEL_TEXT_Y_OFFSET,
  BEST_LABEL_FONT_SIZE,
  BEST_LABEL_OPACITY,
  BEST_PULSE_DUR,
  createTrialClickHandler,
} from "./chartConstants";

type DotPosition = { cx: number; cy: number };

type UseScatterDotParams = {
  dotPositionsRef: React.MutableRefObject<Map<string, DotPosition>>;
  overlapOffsets: Map<string, number>;
  bestCandidateId?: string;
  pulsingCandidateId?: string;
  selectedTrialId?: string;
  onTrialSelect?: (trialId: string) => void;
  onTrialClick?: (candidateId: string) => void;
  isEvaluationSuite?: boolean;
  setHoveredTrial: React.Dispatch<
    React.SetStateAction<{
      candidateId: string;
      cx: number;
      cy: number;
    } | null>
  >;
};

type ScatterDotProps = {
  cx: number;
  cy: number;
  payload: CandidateDataPoint & { x: number };
  [key: string]: unknown;
};

const useScatterDot = ({
  dotPositionsRef,
  overlapOffsets,
  bestCandidateId,
  pulsingCandidateId,
  selectedTrialId,
  onTrialSelect,
  onTrialClick,
  isEvaluationSuite,
  setHoveredTrial,
}: UseScatterDotParams) => {
  return useCallback(
    (props: ScatterDotProps) => {
      const { cx: rawCx, cy, payload } = props;
      const pxOffset = overlapOffsets.get(payload.candidateId) ?? 0;
      const cx = rawCx + pxOffset;
      const color = !isEvaluationSuite
        ? TRIAL_STATUS_COLORS.passed
        : TRIAL_STATUS_COLORS[payload.status];
      const isBest = payload.candidateId === bestCandidateId;
      const isSelected = payload.candidateId === selectedTrialId;
      const radius = isBest ? DOT_RADIUS_BEST : DOT_RADIUS_DEFAULT;

      const handleTrialSelectClick = createTrialClickHandler(
        payload.candidateId,
        onTrialClick,
        onTrialSelect,
      );

      dotPositionsRef.current.set(payload.candidateId, { cx, cy });

      return (
        <g
          key={payload.candidateId}
          onClick={handleTrialSelectClick}
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
              strokeWidth={SELECTION_RING_STROKE_WIDTH}
              r={radius + SELECTION_RING_EXTRA_RADIUS}
              strokeOpacity={SELECTION_RING_STROKE_OPACITY}
            />
          )}
          {pulsingCandidateId === payload.candidateId ? (
            <circle
              cx={cx}
              cy={cy}
              r={radius}
              fill={color}
              strokeWidth={DOT_STROKE_WIDTH}
              stroke={DOT_STROKE_COLOR}
            >
              <animate
                attributeName="opacity"
                values="1;0.4;1"
                dur={BEST_PULSE_DUR}
                repeatCount="indefinite"
              />
            </circle>
          ) : (
            <Dot
              cx={cx}
              cy={cy}
              fill={color}
              strokeWidth={DOT_STROKE_WIDTH}
              stroke={DOT_STROKE_COLOR}
              r={radius}
            />
          )}
          {isBest && (
            <>
              <rect
                x={cx - BEST_LABEL_WIDTH / 2}
                y={cy - radius - BEST_LABEL_Y_OFFSET}
                width={BEST_LABEL_WIDTH}
                height={BEST_LABEL_HEIGHT}
                rx={BEST_LABEL_BORDER_RADIUS}
                fill="hsl(var(--foreground))"
                opacity={BEST_LABEL_OPACITY}
              />
              <text
                x={cx}
                y={cy - radius - BEST_LABEL_TEXT_Y_OFFSET}
                textAnchor="middle"
                fontSize={BEST_LABEL_FONT_SIZE}
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
      pulsingCandidateId,
      selectedTrialId,
      onTrialSelect,
      onTrialClick,
      overlapOffsets,
      isEvaluationSuite,
      dotPositionsRef,
      setHoveredTrial,
    ],
  );
};

export default useScatterDot;
export type { DotPosition };
