import React from "react";

import { OPTIMIZATION_STATUS } from "@/types/optimizations";
import { Tag, TagProps } from "@/ui/tag";

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
  size?: TagProps["size"];
};

const OptimizationStatusTag: React.FC<OptimizationStatusTagProps> = ({
  status,
  size,
}) => (
  <Tag
    size={size}
    className="border border-[var(--pill-neutral-border)] bg-[var(--pill-neutral-bg)] text-foreground"
  >
    <div className="flex items-center gap-1.5">
      <span
        className="size-1.5 shrink-0 rounded-full"
        style={{ backgroundColor: STATUS_DOT_COLOR[status] }}
      />
      <span className="truncate capitalize">{status}</span>
    </div>
  </Tag>
);

export default OptimizationStatusTag;
