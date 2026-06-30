import { OPTIMIZATION_STATUS } from "@/types/optimizations";
import {
  IN_PROGRESS_OPTIMIZATION_STATUSES,
  OPTIMIZATION_ACTIVE_REFETCH_INTERVAL,
} from "@/lib/optimizations";

/**
 * Wall-clock duration (seconds) of a finished optimization run. Uses the run's
 * completion time as the end rather than the last trial's `created_at`, which
 * under-reported by the last trial's own runtime. Returns undefined for
 * missing or non-positive (clock-skew) ranges.
 */
export const getOptimizationDurationSeconds = (
  createdAt?: string,
  endAt?: string,
): number | undefined => {
  if (!createdAt || !endAt) return undefined;
  const start = new Date(createdAt).getTime();
  const end = new Date(endAt).getTime();
  if (Number.isNaN(start) || Number.isNaN(end)) return undefined;
  const seconds = (end - start) / 1000;
  return seconds > 0 ? seconds : undefined;
};

/**
 * Poll the optimization/trials queries only while the run is active; once it
 * finishes there is nothing new to fetch, so the 5s refetch loop should stop.
 */
export const getOptimizationRefetchInterval = (
  status?: OPTIMIZATION_STATUS,
): number | false =>
  status && IN_PROGRESS_OPTIMIZATION_STATUSES.includes(status)
    ? OPTIMIZATION_ACTIVE_REFETCH_INTERVAL
    : false;
