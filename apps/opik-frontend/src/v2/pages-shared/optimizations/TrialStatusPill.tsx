import React from "react";

import { cn } from "@/lib/utils";
import StatusDotPill from "@/v2/pages-shared/optimizations/StatusDotPill";
import {
  TRIAL_BEST_COLOR,
  TRIAL_STATUS_COLORS,
  TRIAL_STATUS_LABELS,
  type TrialStatus,
} from "@/v2/pages-shared/experiments/OptimizationProgressChart/optimizationChartUtils";

type TrialStatusPillProps = {
  status: TrialStatus;
  isBest?: boolean;
  className?: string;
};

/**
 * Trial status pill: the shared `StatusDotPill` with a trial-status-coloured
 * dot and label. The best trial gets the two-tone treatment — the
 * darkest-fuchsia dot on a translucent pale-fuchsia fill. Colours and labels
 * come from the shared trial-status tokens, and the shell is the same primitive
 * the run-list status pill uses, so the trials table, the progress chart and
 * the trial details header always agree.
 */
const TrialStatusPill: React.FC<TrialStatusPillProps> = ({
  status,
  isBest = false,
  className,
}) => (
  <StatusDotPill
    dotColor={isBest ? TRIAL_BEST_COLOR : TRIAL_STATUS_COLORS[status]}
    className={cn(
      isBest &&
        "border-[color-mix(in_srgb,var(--trial-best-ring)_30%,transparent)] bg-[color-mix(in_srgb,var(--trial-best-ring)_25%,transparent)]",
      className,
    )}
  >
    {isBest ? "Best" : TRIAL_STATUS_LABELS[status]}
  </StatusDotPill>
);

export default TrialStatusPill;
