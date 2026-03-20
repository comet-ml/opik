import React from "react";
import isNumber from "lodash/isNumber";

import { AggregatedCandidate } from "@/types/optimizations";
import {
  formatAsPercentage,
  formatAsDuration,
  formatAsCurrency,
} from "@/lib/optimization-formatters";
import { CandidateDataPoint } from "./optimizationChartUtils";
import { TOOLTIP_Y_OFFSET } from "./chartConstants";

type ChartTooltipProps = {
  hoveredTrial: {
    candidateId: string;
    cx: number;
    cy: number;
  };
  candidate: AggregatedCandidate;
  chartData: CandidateDataPoint[];
  isEvaluationSuite?: boolean;
};

const ChartTooltip: React.FC<ChartTooltipProps> = ({
  hoveredTrial,
  candidate,
  chartData,
  isEvaluationSuite,
}) => {
  const chartPoint = chartData.find(
    (d) => d.candidateId === hoveredTrial.candidateId,
  );
  const status = chartPoint?.status ?? "passed";
  const scoreLabel = isEvaluationSuite ? "Pass rate" : "Score";
  const percentageDisplay = isNumber(candidate.score)
    ? formatAsPercentage(candidate.score)
    : "-";
  const fractionDisplay =
    isEvaluationSuite && isNumber(candidate.score) && candidate.totalCount > 0
      ? ` (${candidate.passedCount}/${candidate.totalCount})`
      : "";

  const rows: { label: string; value: string }[] = [
    { label: "Status", value: status },
    {
      label: scoreLabel,
      value: `${percentageDisplay}${fractionDisplay}`,
    },
  ];
  if (candidate.latencyP50 != null) {
    rows.push({
      label: "Latency",
      value: formatAsDuration(candidate.latencyP50),
    });
  }
  if (candidate.runtimeCost != null) {
    rows.push({
      label: "Runtime cost",
      value: formatAsCurrency(candidate.runtimeCost),
    });
  }

  return (
    <div
      className="pointer-events-none min-w-32 max-w-72 rounded-md border border-border px-1 py-1.5 shadow-md"
      style={{
        position: "absolute",
        left: hoveredTrial.cx,
        top: hoveredTrial.cy - TOOLTIP_Y_OFFSET,
        transform: "translate(-50%, -100%)",
        zIndex: 9999,
        backgroundColor: "hsl(var(--background))",
      }}
    >
      <div className="grid items-start gap-1.5">
        <div className="mb-1 max-w-full overflow-hidden border-b px-2 pt-0.5">
          <div className="comet-body-xs-accented mb-0.5 truncate">
            Trial #{candidate.trialNumber}
          </div>
        </div>
        <div className="grid gap-1.5">
          {rows.map((row) => (
            <div key={row.label} className="flex h-6 w-full items-center px-2">
              <div className="flex flex-1 items-center justify-between gap-2 leading-none">
                <span className="comet-body-xs truncate text-muted-slate">
                  {row.label}
                </span>
                <span className="comet-body-xs capitalize">{row.value}</span>
              </div>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
};

export default ChartTooltip;
