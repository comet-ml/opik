import React, { useCallback } from "react";
import { createPortal } from "react-dom";

import { AggregatedCandidate } from "@/types/optimizations";
import { CandidateDataPoint } from "./optimizationChartUtils";
import ChartTooltip from "./ChartTooltip";
import type { DotPosition } from "./ScatterDot";

type UseBestTrialCardParams = {
  dotPositionsRef: React.MutableRefObject<Map<string, DotPosition>>;
  containerRef: React.RefObject<HTMLDivElement>;
  bestCandidateId?: string;
  hoveredCandidateId?: string;
  candidateMap: Map<string, AggregatedCandidate>;
  chartData: CandidateDataPoint[];
  isTestSuite?: boolean;
  /** Skip rendering entirely (e.g. while the trial sidebar is open). */
  suppress?: boolean;
};

/**
 * Default best-trial popover. Rendered through a Recharts <Customized> so it
 * reads the best dot's live position (captured by the Scatter shape into the
 * ref) on every layout — including resize. It reuses the shared ChartTooltip
 * (a Radix popover that positions and flips itself to stay on screen) instead
 * of a hand-clamped card, so the best trial's popover behaves exactly like
 * every other dot's — just opened by default. Shown while nothing else is
 * hovered; hidden as soon as a different dot is hovered (that dot's tooltip
 * takes over).
 */
const useBestTrialCard = ({
  dotPositionsRef,
  containerRef,
  bestCandidateId,
  hoveredCandidateId,
  candidateMap,
  chartData,
  isTestSuite,
  suppress = false,
}: UseBestTrialCardParams) =>
  useCallback(() => {
    if (
      suppress ||
      bestCandidateId == null ||
      containerRef.current == null ||
      (hoveredCandidateId != null && hoveredCandidateId !== bestCandidateId)
    ) {
      return null;
    }

    const pos = dotPositionsRef.current.get(bestCandidateId);
    const best = candidateMap.get(bestCandidateId);
    if (!pos || !best) return null;

    return createPortal(
      <ChartTooltip
        hoveredTrial={{ candidateId: bestCandidateId, cx: pos.cx, cy: pos.cy }}
        candidate={best}
        chartData={chartData}
        isTestSuite={isTestSuite}
        bestCandidateId={bestCandidateId}
        boundaryElement={containerRef.current}
      />,
      containerRef.current,
    );
  }, [
    dotPositionsRef,
    containerRef,
    bestCandidateId,
    hoveredCandidateId,
    candidateMap,
    chartData,
    isTestSuite,
    suppress,
  ]);

export default useBestTrialCard;
