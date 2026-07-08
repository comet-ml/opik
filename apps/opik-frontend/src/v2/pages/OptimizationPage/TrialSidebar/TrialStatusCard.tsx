import React from "react";

import WaypointsIcon from "@/icons/waypoints.svg?react";
import { StatCard } from "@/ui/stat-card";
import { formatDate } from "@/lib/date";
import {
  TRIAL_STATUS_LABELS,
  getTrialDotColor,
  type TrialStatus,
} from "@/v2/pages-shared/experiments/OptimizationProgressChart/optimizationChartUtils";

type TrialStatusCardProps = {
  status?: TrialStatus;
  isBest?: boolean;
  isTestSuite?: boolean;
  stepIndex?: number;
  createdAt?: string;
};

/**
 * The "Details" card in the trial sidebar's stats row: the trial's status as
 * the value, its step as a header tag, and the created date as the caption.
 * The status dot uses the same colour the chart and trials table use.
 */
const TrialStatusCard: React.FC<TrialStatusCardProps> = ({
  status,
  isBest = false,
  isTestSuite,
  stepIndex,
  createdAt,
}) => (
  <StatCard>
    <StatCard.Header>
      <div className="flex min-w-0 flex-1 items-center gap-1">
        {status && (
          <span className="flex size-3 shrink-0 items-center justify-center">
            <span
              className="size-1.5 rounded-full"
              style={{
                backgroundColor: getTrialDotColor({
                  status,
                  isBest,
                  isTestSuite,
                }),
              }}
            />
          </span>
        )}
        <span className="comet-body-xs truncate text-muted-slate">Details</span>
      </div>
      {stepIndex != null && (
        <div className="inline-flex shrink-0 items-center gap-1 rounded-md border bg-background px-2 py-0.5">
          <WaypointsIcon className="size-3 shrink-0 text-muted-slate" />
          <span className="comet-body-xs-accented whitespace-nowrap text-foreground">
            {stepIndex === 0 ? "Baseline" : `Step ${stepIndex}`}
          </span>
        </div>
      )}
    </StatCard.Header>
    <StatCard.Value className={status ? undefined : "text-muted-slate"}>
      {/* Match the trials table, where the best trial reads "Best". */}
      {isBest ? "Best" : status ? TRIAL_STATUS_LABELS[status] : "-"}
    </StatCard.Value>
    {createdAt && <StatCard.Caption>{formatDate(createdAt)}</StatCard.Caption>}
  </StatCard>
);

export default TrialStatusCard;
