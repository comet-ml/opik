import React, { useCallback } from "react";
import { createPortal } from "react-dom";

import { cn } from "@/lib/utils";
import { AggregatedCandidate } from "@/types/optimizations";
import { CandidateDataPoint } from "./optimizationChartUtils";
import { TOOLTIP_Y_OFFSET } from "./chartConstants";
import TrialCard, { TRIAL_CARD_SHELL_CLASS } from "./TrialCard";
import type { DotPosition } from "./ScatterDot";

type UseBestTrialCardParams = {
  dotPositionsRef: React.MutableRefObject<Map<string, DotPosition>>;
  containerRef: React.RefObject<HTMLDivElement>;
  bestCandidateId?: string;
  hoveredCandidateId?: string;
  candidateMap: Map<string, AggregatedCandidate>;
  chartData: CandidateDataPoint[];
  isTestSuite?: boolean;
};

/**
 * Pinned best-trial card. Rendered through a Recharts <Customized> so it
 * reads the best dot's live position (captured by the Scatter shape into the
 * ref) on every layout — including resize — then portals an HTML card that
 * hangs below the dot, matching Figma. Kept while the best dot itself is
 * hovered (it *is* the best trial's popover); hidden only while a different
 * dot is hovered (the hover tooltip takes over).
 */
const useBestTrialCard = ({
  dotPositionsRef,
  containerRef,
  bestCandidateId,
  hoveredCandidateId,
  candidateMap,
  chartData,
  isTestSuite,
}: UseBestTrialCardParams) =>
  useCallback(() => {
    if (
      bestCandidateId == null ||
      containerRef.current == null ||
      (hoveredCandidateId != null && hoveredCandidateId !== bestCandidateId)
    ) {
      return null;
    }

    const pos = dotPositionsRef.current.get(bestCandidateId);
    const best = candidateMap.get(bestCandidateId);
    if (!pos || !best) return null;

    const bestPoint = chartData.find((d) => d.candidateId === bestCandidateId);

    return createPortal(
      <div
        className={cn(
          "pointer-events-none absolute z-10 rounded-md border bg-background shadow-md",
          TRIAL_CARD_SHELL_CLASS,
        )}
        style={{
          left: pos.cx,
          top: pos.cy + TOOLTIP_Y_OFFSET,
          transform: "translateX(-50%)",
        }}
      >
        <TrialCard
          candidate={best}
          status={bestPoint?.status ?? "passed"}
          stepIndex={bestPoint?.stepIndex ?? 0}
          isTestSuite={isTestSuite}
          isBest
        />
      </div>,
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
  ]);

export default useBestTrialCard;
