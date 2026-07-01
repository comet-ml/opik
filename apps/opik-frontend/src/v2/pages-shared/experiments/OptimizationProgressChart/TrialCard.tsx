import React from "react";
import isNumber from "lodash/isNumber";

import { AggregatedCandidate } from "@/types/optimizations";
import {
  formatAsPercentage,
  formatAsDuration,
  formatAsCurrency,
} from "@/lib/optimization-formatters";
import {
  CandidateDataPoint,
  TRIAL_STATUS_COLORS,
  TRIAL_BEST_COLOR,
  getTrialStatusLabel,
} from "./optimizationChartUtils";

type TrialCardProps = {
  candidate: AggregatedCandidate;
  status: CandidateDataPoint["status"];
  stepIndex: number;
  isTestSuite?: boolean;
  isBest?: boolean;
};

/**
 * Card body for a single trial — used both as the hover tooltip (positioned by
 * ChartTooltip) and as the pinned best-trial card shown by default. The header
 * carries the trial name plus a status dot + label (Figma: "Passed step 1",
 * "Discarded in step 2", "Best trial"); the metrics follow below a divider.
 */
const TrialCard: React.FC<TrialCardProps> = ({
  candidate,
  status,
  stepIndex,
  isTestSuite,
  isBest,
}) => {
  const scoreLabel = isTestSuite ? "Pass rate" : "Score";
  const percentageDisplay = isNumber(candidate.score)
    ? formatAsPercentage(candidate.score)
    : "-";
  const fractionDisplay =
    isTestSuite && isNumber(candidate.score) && candidate.totalCount > 0
      ? ` (${candidate.passedCount}/${candidate.totalCount})`
      : "";

  const statusLabel = isBest
    ? "Best trial"
    : getTrialStatusLabel(status, stepIndex);
  const dotColor = isBest ? TRIAL_BEST_COLOR : TRIAL_STATUS_COLORS[status];

  const rows: { label: string; value: string }[] = [
    { label: scoreLabel, value: `${percentageDisplay}${fractionDisplay}` },
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
    <div className="w-[220px] rounded-md border border-border bg-background p-1.5 shadow-md">
      <div className="flex min-h-[22px] items-center gap-1.5 px-1">
        <span className="comet-body-xs-accented flex-1 truncate text-foreground">
          Trial #{candidate.trialNumber}
        </span>
        <span className="flex shrink-0 items-center gap-1.5">
          <span
            className="size-1.5 shrink-0 rounded-full"
            style={{ backgroundColor: dotColor }}
          />
          <span className="comet-body-xs-accented whitespace-nowrap text-foreground">
            {statusLabel}
          </span>
        </span>
      </div>
      <div className="py-1">
        <div className="h-px w-full bg-border" />
      </div>
      {rows.map((row) => (
        <div key={row.label} className="flex h-6 items-center gap-1.5 px-1">
          <span className="comet-body-xs flex-1 truncate text-muted-slate">
            {row.label}
          </span>
          <span className="comet-body-xs-accented shrink-0 text-right text-foreground">
            {row.value}
          </span>
        </div>
      ))}
    </div>
  );
};

export default TrialCard;
