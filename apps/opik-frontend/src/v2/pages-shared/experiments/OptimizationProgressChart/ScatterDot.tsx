import React, { useCallback } from "react";
import { Dot } from "recharts";

import {
  TRIAL_BEST_RING_COLOR,
  getTrialDotColor,
  CandidateDataPoint,
} from "./optimizationChartUtils";
import {
  getDotRadius,
  SELECTION_RING_EXTRA_RADIUS,
  SELECTION_RING_STROKE_WIDTH,
  SELECTION_RING_STROKE_OPACITY,
  DOT_BEST_RING_WIDTH,
  DOT_STROKE_WIDTH,
  DOT_STROKE_COLOR,
  MINI_BATCH_DOT_RADIUS,
  MINI_BATCH_DOT_STROKE_WIDTH,
  MINI_BATCH_DOT_OPACITY,
  BEST_LABEL_WIDTH,
  BEST_LABEL_HEIGHT,
  BEST_LABEL_BORDER_RADIUS,
  BEST_LABEL_FONT_SIZE,
  BEST_LABEL_TAIL_WIDTH,
  BEST_LABEL_TAIL_HEIGHT,
  BEST_LABEL_GAP,
  BEST_PULSE_DUR,
} from "./chartConstants";

type DotPosition = { cx: number; cy: number };

type UseScatterDotParams = {
  dotPositionsRef: React.MutableRefObject<Map<string, DotPosition>>;
  overlapOffsets: Map<string, number>;
  bestCandidateId?: string;
  hoveredCandidateId?: string;
  pulsingCandidateId?: string;
  selectedTrialId?: string;
  isTestSuite?: boolean;
};

type ScatterDotProps = {
  cx: number;
  cy: number;
  payload: CandidateDataPoint & { x: number };
  [key: string]: unknown;
};

// Dots are purely visual and never capture pointer events — hover and click are
// resolved by a single nearest-dot handler on the chart container (see
// OptimizationProgressChartContent). This avoids the overlapping per-dot hit
// areas that made clustered dots impossible to target and flickered the hover.
const useScatterDot = ({
  dotPositionsRef,
  overlapOffsets,
  bestCandidateId,
  hoveredCandidateId,
  pulsingCandidateId,
  selectedTrialId,
  isTestSuite,
}: UseScatterDotParams) => {
  return useCallback(
    (props: ScatterDotProps) => {
      const { cx: rawCx, cy, payload } = props;
      const pxOffset = overlapOffsets.get(payload.candidateId) ?? 0;
      const cx = rawCx + pxOffset;
      const isBest = payload.candidateId === bestCandidateId;
      const isMiniBatch = payload.kind === "minibatch";
      const color = getTrialDotColor({
        status: payload.status,
        isBest,
        isTestSuite,
      });
      const isSelected = payload.candidateId === selectedTrialId;
      // The best dot's popover is shown by default, so it stays in its hovered
      // (grown) state — no separate active styling needed.
      const isHovered = isBest || payload.candidateId === hoveredCandidateId;
      const radius = getDotRadius({ isBest, isHovered });

      dotPositionsRef.current.set(payload.candidateId, { cx, cy });

      // Mini-batch screening evals render as small hollow rings so they read
      // as search activity, visually subordinate to the solid full-eval dots.
      if (isMiniBatch) {
        return (
          <g key={payload.candidateId} pointerEvents="none">
            <circle
              cx={cx}
              cy={cy}
              r={MINI_BATCH_DOT_RADIUS + (isHovered ? 1 : 0)}
              fill={DOT_STROKE_COLOR}
              stroke={color}
              strokeWidth={MINI_BATCH_DOT_STROKE_WIDTH}
              opacity={MINI_BATCH_DOT_OPACITY}
            />
          </g>
        );
      }

      return (
        <g key={payload.candidateId} pointerEvents="none">
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
          {/* Halo drawn behind the fill so the border sits outside the dot:
              the best dot gets a fuchsia-300 ring, every other dot a 1.5px
              white/background border so it reads crisply over the trend line
              and grid. */}
          <circle
            cx={cx}
            cy={cy}
            r={radius + (isBest ? DOT_BEST_RING_WIDTH : DOT_STROKE_WIDTH)}
            fill={isBest ? TRIAL_BEST_RING_COLOR : DOT_STROKE_COLOR}
          />
          {pulsingCandidateId === payload.candidateId ? (
            <circle cx={cx} cy={cy} r={radius} fill={color}>
              <animate
                attributeName="opacity"
                values="1;0.4;1"
                dur={BEST_PULSE_DUR}
                repeatCount="indefinite"
              />
            </circle>
          ) : (
            <Dot cx={cx} cy={cy} fill={color} r={radius} />
          )}
          {isBest &&
            (() => {
              // Fuchsia-300 pill with dark text and a downward tail pointing
              // at the dot.
              const tailTipY = cy - radius - BEST_LABEL_GAP;
              const tailBaseY = tailTipY - BEST_LABEL_TAIL_HEIGHT;
              const pillTop = tailBaseY - BEST_LABEL_HEIGHT;
              return (
                <>
                  <rect
                    x={cx - BEST_LABEL_WIDTH / 2}
                    y={pillTop}
                    width={BEST_LABEL_WIDTH}
                    height={BEST_LABEL_HEIGHT}
                    rx={BEST_LABEL_BORDER_RADIUS}
                    fill={TRIAL_BEST_RING_COLOR}
                  />
                  <path
                    d={`M${cx - BEST_LABEL_TAIL_WIDTH / 2},${tailBaseY} L${
                      cx + BEST_LABEL_TAIL_WIDTH / 2
                    },${tailBaseY} L${cx},${tailTipY} Z`}
                    fill={TRIAL_BEST_RING_COLOR}
                  />
                  <text
                    x={cx}
                    y={pillTop + BEST_LABEL_HEIGHT / 2}
                    textAnchor="middle"
                    dominantBaseline="central"
                    fontSize={BEST_LABEL_FONT_SIZE}
                    fontWeight={500}
                    fill="hsl(var(--foreground))"
                  >
                    Best trial
                  </text>
                </>
              );
            })()}
        </g>
      );
    },
    [
      bestCandidateId,
      hoveredCandidateId,
      pulsingCandidateId,
      selectedTrialId,
      overlapOffsets,
      isTestSuite,
      dotPositionsRef,
    ],
  );
};

export default useScatterDot;
export type { DotPosition };
