import { createFilter } from "@/lib/filters";
import { OPTIMIZATION_STATUS } from "@/types/optimizations";

// TODO lala fix filter
export const ACTIVE_OPTIMIZATION_FILTER = [
  createFilter({
    field: "status",
    operator: "=",
    value: OPTIMIZATION_STATUS.RUNNING,
  }),
];
