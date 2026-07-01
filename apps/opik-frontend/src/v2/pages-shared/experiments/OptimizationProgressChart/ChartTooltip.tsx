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
  isBest?: boolean;
};

/**
 * Trial card shown over the hovered dot. Built on the core Popover so it
 * portals above the app chrome (no longer clipped behind the side menu),
 * animates in, and reuses the shared popover shell. An invisible anchor is
 * pinned at the dot's coordinates; the content flips/shifts to stay on screen.
 */
const ChartTooltip: React.FC<ChartTooltipProps> = ({
  hoveredTrial,
  candidate,
  chartData,
  isTestSuite,
  isBest,
}) => {
  const chartPoint = chartData.find(
    (d) => d.candidateId === hoveredTrial.candidateId,
  );
  const status = chartPoint?.status ?? "passed";
  const stepIndex = chartPoint?.stepIndex ?? 0;

  return (
    <Popover open>
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
