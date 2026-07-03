import React from "react";

import { cn } from "@/lib/utils";
import OptimizationConfigPill from "@/v2/pages-shared/optimizations/OptimizationConfigPill";
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
 * Trial status pill: a soft pill with a status-coloured dot and label. The
 * best trial gets the two-tone treatment —
 * the darkest-fuchsia dot on a translucent pale-fuchsia fill. Colours and
 * labels come from the shared trial-status tokens, so the trials table, the
 * progress chart and the trial details header always agree.
 */
const TrialStatusPill: React.FC<TrialStatusPillProps> = ({
  status,
  isBest = false,
  className,
}) => (
  <OptimizationConfigPill
    className={cn(
      "shrink-0 bg-primary-foreground pl-1 pr-1.5",
      isBest &&
        "border-[color-mix(in_srgb,var(--trial-best-ring)_30%,transparent)] bg-[color-mix(in_srgb,var(--trial-best-ring)_25%,transparent)]",
      className,
    )}
    icon={
      <span
        className="size-1.5 shrink-0 rounded-full"
        style={{
          backgroundColor: isBest
            ? TRIAL_BEST_COLOR
            : TRIAL_STATUS_COLORS[status],
        }}
      />
    }
  >
    {isBest ? "Best" : TRIAL_STATUS_LABELS[status]}
  </OptimizationConfigPill>
);

export default TrialStatusPill;
