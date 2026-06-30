import React from "react";

import { AggregatedCandidate } from "@/types/optimizations";
import { CandidateDataPoint } from "./optimizationChartUtils";
import { TOOLTIP_Y_OFFSET } from "./chartConstants";
import TrialCard from "./TrialCard";

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

/** Trial card positioned over the hovered dot. */
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

  return (
    <div
      className="pointer-events-none"
      style={{
        position: "absolute",
        left: hoveredTrial.cx,
        top: hoveredTrial.cy - TOOLTIP_Y_OFFSET,
        transform: "translate(-50%, -100%)",
        zIndex: 9999,
      }}
    >
      <TrialCard
        candidate={candidate}
        status={status}
        isTestSuite={isTestSuite}
        isBest={isBest}
      />
    </div>
  );
};

export default ChartTooltip;
