import React from "react";
import isNumber from "lodash/isNumber";

import { AggregatedCandidate } from "@/types/optimizations";
import {
  formatAsPercentage,
  formatAsDuration,
  formatAsCurrency,
} from "@/lib/optimization-formatters";
import { CandidateDataPoint } from "./optimizationChartUtils";

type TrialCardProps = {
  candidate: AggregatedCandidate;
  status: CandidateDataPoint["status"];
  isTestSuite?: boolean;
  isBest?: boolean;
};

/**
 * Card body for a single trial — used both as the hover tooltip (positioned by
 * ChartTooltip) and as the pinned best-trial card shown by default. The best
 * card carries a "Best trial" badge instead of a status row (matching Figma).
 */
const TrialCard: React.FC<TrialCardProps> = ({
  candidate,
  status,
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

  const rows: { label: string; value: string }[] = [];
  if (!isBest) {
    rows.push({ label: "Status", value: status });
  }
  rows.push({
    label: scoreLabel,
    value: `${percentageDisplay}${fractionDisplay}`,
  });
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
    <div className="min-w-32 max-w-72 rounded-md border border-border bg-background px-1 py-1.5 shadow-md">
      <div className="grid items-start gap-1.5">
        <div className="mb-1 flex items-center justify-between gap-2 border-b px-2 pb-1.5 pt-0.5">
          <span className="comet-body-xs-accented truncate">
            Trial #{candidate.trialNumber}
          </span>
          {isBest && (
            <span className="flex shrink-0 items-center gap-1">
              <span className="size-1.5 rounded-full bg-primary" />
              <span className="comet-body-xs text-muted-slate">Best trial</span>
            </span>
          )}
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

export default TrialCard;
