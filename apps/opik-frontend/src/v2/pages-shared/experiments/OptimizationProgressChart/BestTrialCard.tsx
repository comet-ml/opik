import React, { useCallback } from "react";
import { createPortal } from "react-dom";

import { cn } from "@/lib/utils";
import { AggregatedCandidate } from "@/types/optimizations";
import { CandidateDataPoint } from "./optimizationChartUtils";
import { TOOLTIP_Y_OFFSET } from "./chartConstants";
import TrialCard, {
  TRIAL_CARD_SHELL_CLASS,
  TRIAL_CARD_WIDTH,
} from "./TrialCard";
import type { DotPosition } from "./ScatterDot";

/** Minimum gap between the pinned card and the chart container's edges. */
const CARD_EDGE_GAP = 4;

// Estimated card height, used to keep the card inside the chart's vertical band
// (mirrors the horizontal clamp). Keep in sync with TrialCard's layout: shell
// padding (p-1.5), header (min-h-[22px]), the divider (py-1 + 1px rule), then
// one row (h-6) per metric.
const CARD_SHELL_V_PADDING = 12;
const CARD_HEADER_HEIGHT = 22;
const CARD_DIVIDER_HEIGHT = 9;
const CARD_ROW_HEIGHT = 24;

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
 * hangs below the dot. Kept while the best dot itself is
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

    // Center the card under the dot, but clamp it so it never escapes the
    // surrounding panel — a dot near the chart's edge (e.g. a baseline-only
    // run) must not push the card over the side menu or adjacent panels. The
    // clamp bound is the panel's border (data-chart-panel), so the card may
    // overhang the chart's inner padding; fall back to the chart container
    // itself when no panel wraps it.
    const containerRect = containerRef.current.getBoundingClientRect();
    const panelRect = containerRef.current
      .closest("[data-chart-panel]")
      ?.getBoundingClientRect();
    const minLeft = panelRect
      ? panelRect.left - containerRect.left
      : CARD_EDGE_GAP;
    const maxLeft = panelRect
      ? panelRect.right - containerRect.left - TRIAL_CARD_WIDTH
      : containerRect.width - TRIAL_CARD_WIDTH - CARD_EDGE_GAP;
    const left = Math.max(
      minLeft,
      Math.min(pos.cx - TRIAL_CARD_WIDTH / 2, maxLeft),
    );

    // Clamp the card vertically the same way, so a low-scoring best dot doesn't
    // push it past the chart's bottom edge onto the legend / links below. The
    // chart plot is the first child (the ChartContainer); the card must stay
    // within it. Card height is estimated from its metric-row count.
    const rowCount =
      1 +
      (best.latencyP50 != null ? 1 : 0) +
      (best.runtimeCost != null ? 1 : 0);
    const cardHeight =
      CARD_SHELL_V_PADDING +
      CARD_HEADER_HEIGHT +
      CARD_DIVIDER_HEIGHT +
      rowCount * CARD_ROW_HEIGHT;
    const chartHeight =
      (containerRef.current.firstElementChild as HTMLElement | null)
        ?.clientHeight ?? containerRect.height;
    const maxTop = chartHeight - cardHeight - CARD_EDGE_GAP;
    const top = Math.max(
      CARD_EDGE_GAP,
      Math.min(pos.cy + TOOLTIP_Y_OFFSET, maxTop),
    );

    return createPortal(
      <div
        className={cn(
          "pointer-events-none absolute z-10 rounded-md border bg-background shadow-md",
          TRIAL_CARD_SHELL_CLASS,
        )}
        style={{
          left,
          top,
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
