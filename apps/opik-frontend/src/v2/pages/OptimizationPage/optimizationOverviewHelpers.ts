import dayjs from "dayjs";

import {
  AggregatedCandidate,
  OPTIMIZATION_STATUS,
} from "@/types/optimizations";
import {
  IN_PROGRESS_OPTIMIZATION_STATUSES,
  OPTIMIZATION_ACTIVE_REFETCH_INTERVAL,
} from "@/lib/optimizations";

/**
 * Wall-clock duration (seconds) of a finished optimization run. Uses the run's
 * completion time as the end rather than the last trial's `created_at`, which
 * under-reported by the last trial's own runtime. Returns undefined for
 * missing, unparseable, or non-positive (clock-skew) ranges.
 */
export const getOptimizationDurationSeconds = (
  createdAt?: string,
  endAt?: string,
): number | undefined => {
  if (!createdAt || !endAt) return undefined;
  const start = dayjs(createdAt);
  const end = dayjs(endAt);
  if (!start.isValid() || !end.isValid()) return undefined;
  // `true` returns fractional seconds (matching the previous ms/1000 math).
  const seconds = end.diff(start, "second", true);
  return seconds > 0 ? seconds : undefined;
};

type CompletedRunDurationParams = {
  isInProgress?: boolean;
  optimizationCreatedAt?: string;
  optimizationLastUpdatedAt?: string;
  /** `created_at` of each trial the run produced. */
  trialCreatedTimes: string[];
};

/**
 * Total wall-clock duration (seconds) of a run, e.g. "4m 25s total" on the
 * Optimization cost card. Only defined once the run has finished and produced
 * at least one trial.
 *
 * The run's end is its completion time (`last_updated_at`); when that is
 * unavailable, fall back to the newest trial's `created_at` — slightly
 * under-reported (it misses the last trial's own runtime) but the best
 * remaining signal.
 */
export const getCompletedRunDurationSeconds = ({
  isInProgress,
  optimizationCreatedAt,
  optimizationLastUpdatedAt,
  trialCreatedTimes,
}: CompletedRunDurationParams): number | undefined => {
  if (isInProgress || trialCreatedTimes.length === 0) return undefined;

  // ISO timestamps compare lexicographically, so string max is the latest.
  const latestTrialCreatedAt = trialCreatedTimes.reduce((latest, time) =>
    time > latest ? time : latest,
  );

  return getOptimizationDurationSeconds(
    optimizationCreatedAt,
    optimizationLastUpdatedAt ?? latestTrialCreatedAt,
  );
};

/**
 * Heuristic detector for a "silent COMPLETED" run — the OPIK-7029 gap where a
 * run finishes normally but every evaluation failed to score, so it looks like
 * a plain empty run (dashes, "No data to show") with no error or warning.
 *
 * The rule: the run is terminal-COMPLETED **and** no candidate that actually
 * ran an optimization step produced a usable score. The baseline (stepIndex 0)
 * is deliberately excluded from the "did anything score?" check — a scored
 * baseline is expected on every run and does not mean the optimizer produced
 * anything, so a run whose only score is the baseline is still degenerate.
 * A run with zero non-baseline candidates counts as empty too (the optimizer
 * generated nothing to evaluate).
 *
 * This is a client-only heuristic; it can't tell a genuine all-zero run from an
 * all-failed one (Wave 2 threads exact scoring-health counts from the backend).
 * It only fires on COMPLETED — ERROR runs are already handled by RunErrorPanel,
 * and in-progress runs legitimately have unscored candidates.
 */
export const computeEmptyRunWarning = (
  candidates: AggregatedCandidate[],
  status?: OPTIMIZATION_STATUS,
): boolean => {
  if (status !== OPTIMIZATION_STATUS.COMPLETED) return false;

  const nonBaselineCandidates = candidates.filter((c) => c.stepIndex !== 0);
  // No trials at all, or none of the trials scored → no usable optimization result.
  return nonBaselineCandidates.every((c) => c.score == null);
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
