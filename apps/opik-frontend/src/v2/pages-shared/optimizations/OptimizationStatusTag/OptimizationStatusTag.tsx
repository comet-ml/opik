import React from "react";

import { OPTIMIZATION_STATUS } from "@/types/optimizations";
import StatusDotPill from "@/v2/pages-shared/optimizations/StatusDotPill";

// A run status is a neutral pill with a colored status dot: running = green,
// completed/initialized = slate, cancelled/error = red. Uses the shared brand
// tokens (same green/red as PercentageTrend) so status and trend colors stay in
// sync.
const STATUS_DOT_COLOR: Record<OPTIMIZATION_STATUS, string> = {
  [OPTIMIZATION_STATUS.RUNNING]: "var(--color-green-bright)",
  [OPTIMIZATION_STATUS.COMPLETED]: "hsl(var(--light-slate))",
  [OPTIMIZATION_STATUS.CANCELLED]: "var(--color-red)",
  [OPTIMIZATION_STATUS.INITIALIZED]: "hsl(var(--light-slate))",
  [OPTIMIZATION_STATUS.ERROR]: "var(--color-red)",
};

type OptimizationStatusTagProps = {
  status: OPTIMIZATION_STATUS;
};

// The shared status pill; `capitalize` cascades to the label span (the raw
// status value is lower-case).
const OptimizationStatusTag: React.FC<OptimizationStatusTagProps> = ({
  status,
}) => (
  <StatusDotPill dotColor={STATUS_DOT_COLOR[status]} className="capitalize">
    {status}
  </StatusDotPill>
);

export default OptimizationStatusTag;
