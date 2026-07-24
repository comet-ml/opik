import dayjs from "dayjs";

import {
  AggregatedCandidate,
  OPTIMIZATION_STATUS,
  OptimizationScoringHealth,
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
 * Produces the user-facing body message for the empty-run warning panel and the
 * KPI score-card caption. Two code paths:
 *
 *  1. **Exact count** (Wave 2, OPIK-7159): when `scoring_health` is present
 *     and `total_count > 0`, the backend persisted the real numbers. The copy
 *     uses `failed_count` / `total_count` directly and distinguishes:
 *       - all failed  → "All N items failed to score …"
 *       - partial     → "N of M items failed to score …"
 *       - singular    → "1 item" not "1 items"
 *
 *  2. **Heuristic fallback** (Wave 1, no backend data): returns the static
 *     message that was already shown before Wave 2 — exact backward compat.
 *
 * Returns `null` when the health data says nothing failed (failed_count === 0),
 * which lets the caller skip rendering the warning entirely.
 */
export const getEmptyRunWarningMessage = (
  scoringHealth?: OptimizationScoringHealth,
): string | null => {
  // --- Exact-count path (backend-provided, OPIK-7159 Wave 2) ---
  if (scoringHealth && scoringHealth.total_count > 0) {
    const { failed_count, total_count } = scoringHealth;

    if (failed_count === 0) {
      // Backend says nothing failed — suppress the warning.
      return null;
    }

    if (failed_count >= total_count) {
      // Every item failed — use the stronger framing. The noun agrees with
      // total_count, so a one-item dataset reads "The item …" not "All 1 item …".
      const lead =
        total_count === 1
          ? "The item failed to score."
          : `All ${total_count} items failed to score.`;
      return (
        `${lead} ` +
        "The metric may have errored on every evaluation. " +
        "Open the logs, check the metric and model, then run it again."
      );
    }

    // Partial failure — softer framing. A partial failure always has
    // total_count >= 2 (failed_count is >= 1 and strictly less than total),
    // so the noun is always plural ("1 of 5 items", never "1 of 5 item").
    return (
      `${failed_count} of ${total_count} items failed to score. ` +
      "Some evaluations did not produce a usable result. " +
      "Open the logs to see which items failed, then run it again."
    );
  }

  // --- Heuristic fallback (Wave 1, no backend data) ---
  return "This run finished but produced no usable scores — the metric may have failed on every item. Open the logs, check the metric and model, then run it again.";
};

/**
 * Shortened version of {@link getEmptyRunWarningMessage} for the KPI score-card
 * caption, where space is tight. Returns `null` for the same conditions
 * (nothing failed, or scoring_health absent but `isEmptyRun` is false).
 *
 * When `isEmptyRun` is false and `scoring_health` is absent, returns null —
 * callers gate on `isEmptyRun` already, so this helper is only called when a
 * warning is appropriate.
 */
export const getEmptyRunKPICaption = (
  isEmptyRun: boolean,
  scoringHealth?: OptimizationScoringHealth,
): string | null => {
  if (!isEmptyRun) return null;

  if (scoringHealth && scoringHealth.total_count > 0) {
    const { failed_count, total_count } = scoringHealth;
    if (failed_count === 0) return null;

    if (failed_count >= total_count) {
      return total_count === 1
        ? "The item failed to score — check the logs."
        : `All ${total_count} items failed to score — check the logs.`;
    }
    return `${failed_count} of ${total_count} items failed to score — check the logs.`;
  }

  // Heuristic fallback (Wave 1).
  return "No usable scores — check the logs.";
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
