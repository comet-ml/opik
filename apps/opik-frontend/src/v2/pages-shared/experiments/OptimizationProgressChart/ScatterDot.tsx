import React, { useCallback } from "react";
import { Dot } from "recharts";

import {
  TRIAL_STATUS_COLORS,
  TRIAL_BEST_COLOR,
  TRIAL_BEST_RING_COLOR,
  CandidateDataPoint,
} from "./optimizationChartUtils";
import {
  getDotRadius,
  HIT_AREA_RADIUS,
  SELECTION_RING_EXTRA_RADIUS,
  SELECTION_RING_STROKE_WIDTH,
  SELECTION_RING_STROKE_OPACITY,
  DOT_BEST_RING_WIDTH,
  DOT_STROKE_WIDTH,
  DOT_STROKE_COLOR,
  BEST_LABEL_WIDTH,
  BEST_LABEL_HEIGHT,
  BEST_LABEL_BORDER_RADIUS,
  BEST_LABEL_FONT_SIZE,
  BEST_LABEL_TAIL_WIDTH,
  BEST_LABEL_TAIL_HEIGHT,
  BEST_LABEL_GAP,
  BEST_PULSE_DUR,
  createTrialClickHandler,
} from "./chartConstants";

type DotPosition = { cx: number; cy: number };

type UseScatterDotParams = {
  dotPositionsRef: React.MutableRefObject<Map<string, DotPosition>>;
  overlapOffsets: Map<string, number>;
  bestCandidateId?: string;
  hoveredCandidateId?: string;
  pulsingCandidateId?: string;
  selectedTrialId?: string;
  onTrialSelect?: (trialId: string) => void;
  onTrialClick?: (candidateId: string) => void;
  isTestSuite?: boolean;
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
  hoveredCandidateId,
  pulsingCandidateId,
  selectedTrialId,
  onTrialSelect,
  onTrialClick,
  isTestSuite,
  setHoveredTrial,
}: UseScatterDotParams) => {
  return useCallback(
    (props: ScatterDotProps) => {
      const { cx: rawCx, cy, payload } = props;
      const pxOffset = overlapOffsets.get(payload.candidateId) ?? 0;
      const cx = rawCx + pxOffset;
      const isBest = payload.candidateId === bestCandidateId;
      const color = isBest
        ? TRIAL_BEST_COLOR
        : !isTestSuite
          ? TRIAL_STATUS_COLORS.passed
          : TRIAL_STATUS_COLORS[payload.status];
      const isSelected = payload.candidateId === selectedTrialId;
      // The best dot's popover is always shown, so it stays in its hovered
      // (grown) state by default — no separate active styling needed.
      const isHovered = isBest || payload.candidateId === hoveredCandidateId;
      const radius = getDotRadius({ isBest, isHovered });

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
          {/* Fixed, enlarged transparent hit area. It captures the hover so the
              target stays constant — the visible dot's grow-on-hover never
              moves the boundary (no flicker) and the dot is easy to hover. */}
          <circle
            cx={cx}
            cy={cy}
            r={HIT_AREA_RADIUS}
            fill="transparent"
            pointerEvents="all"
          />
          {/* Visible marks never capture pointer events — only the hit area does. */}
          <g pointerEvents="none">
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
            {/* Halo drawn behind the fill so the border sits outside the dot
                (per Figma): the best dot gets a fuchsia-300 ring, every other
                dot a 1.5px white/background border so it reads crisply over the
                trend line and grid. */}
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
                // at the dot (Figma node 686:51916).
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
        </g>
      );
    },
    [
      bestCandidateId,
      hoveredCandidateId,
      pulsingCandidateId,
      selectedTrialId,
      onTrialSelect,
      onTrialClick,
      overlapOffsets,
      isTestSuite,
      dotPositionsRef,
      setHoveredTrial,
    ],
  );
};

export default useScatterDot;
export type { DotPosition };
