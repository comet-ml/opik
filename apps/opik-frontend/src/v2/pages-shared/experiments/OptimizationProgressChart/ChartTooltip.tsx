import React from "react";

import { AggregatedCandidate } from "@/types/optimizations";
import { Popover, PopoverAnchor, PopoverContent } from "@/ui/popover";
import { cn } from "@/lib/utils";
import { CandidateDataPoint } from "./optimizationChartUtils";
import { TOOLTIP_Y_OFFSET } from "./chartConstants";
import TrialCard, { TRIAL_CARD_SHELL_CLASS } from "./TrialCard";

type ChartTooltipProps = {
  hoveredTrial: {
    candidateId: string;
    cx: number;
    cy: number;
  };
  candidate: AggregatedCandidate;
  chartData: CandidateDataPoint[];
  isTestSuite?: boolean;
  bestCandidateId?: string;
  /** Constrain the popover to this element (the chart container) so it flips /
   *  shifts to stay inside the chart rather than overflowing onto surrounding
   *  content. */
  boundaryElement?: Element | null;
};

/**
 * Trial card shown over a dot — the hovered dot, or the best trial by default
 * (see useBestTrialCard, which reuses this same popover). Built on the core
 * Popover so it portals above the app chrome (no longer clipped behind the side
 * menu), animates in, and reuses the shared popover shell. An invisible anchor
 * is pinned at the dot's coordinates; the content flips/shifts to stay on
 * screen. When the dot is the best trial, the card shows the "Best trial"
 * header styling.
 */
const ChartTooltip: React.FC<ChartTooltipProps> = ({
  hoveredTrial,
  candidate,
  chartData,
  isTestSuite,
  bestCandidateId,
  boundaryElement,
}) => {
  const chartPoint = chartData.find(
    (d) => d.candidateId === hoveredTrial.candidateId,
  );
  const status = chartPoint?.status ?? "passed";
  const stepIndex = chartPoint?.stepIndex ?? 0;
  const isBest = hoveredTrial.candidateId === bestCandidateId;

  return (
    // Keyed by candidate so the popover remounts when the target dot changes —
    // Radix only positions on mount, so without this the card content updates
    // but stays anchored to the previous dot when moving quickly between dots.
    <Popover key={hoveredTrial.candidateId} open>
      <PopoverAnchor asChild>
        <div
          className="absolute size-0"
          style={{ left: hoveredTrial.cx, top: hoveredTrial.cy }}
        />
      </PopoverAnchor>
      <PopoverContent
        side="top"
        align="center"
        sideOffset={TOOLTIP_Y_OFFSET}
        collisionBoundary={boundaryElement ?? undefined}
        collisionPadding={4}
        onOpenAutoFocus={(e) => e.preventDefault()}
        className={cn("pointer-events-none", TRIAL_CARD_SHELL_CLASS)}
      >
        <TrialCard
          candidate={candidate}
          status={status}
          stepIndex={stepIndex}
          isTestSuite={isTestSuite}
          isBest={isBest}
        />
      </PopoverContent>
    </Popover>
  );
};

export default ChartTooltip;
