import React from "react";

import { OPTIMIZATION_STATUS } from "@/types/optimizations";
import { Tag, TagProps } from "@/ui/tag";

// Per Figma 562:37189 a run status is a neutral pill with a colored status dot
// (not a colored-background badge): running = green, completed/initialized =
// slate, cancelled/error = red. Shared so the list, run-detail header and
// trials render the status identically.
const STATUS_DOT_COLOR: Record<OPTIMIZATION_STATUS, string> = {
  [OPTIMIZATION_STATUS.RUNNING]: "#00d14c",
  [OPTIMIZATION_STATUS.COMPLETED]: "#94a3b8",
  [OPTIMIZATION_STATUS.CANCELLED]: "#ef4444",
  [OPTIMIZATION_STATUS.INITIALIZED]: "#94a3b8",
  [OPTIMIZATION_STATUS.ERROR]: "#ef4444",
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
    className="border border-[#f1f5f9] bg-[#f8fafc] text-foreground"
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
