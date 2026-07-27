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
 * Why a COMPLETED run has nothing usable to show. Previously a single boolean,
 * which made the panel blame the metric even when the metric worked (OPIK-7458).
 */
export const EMPTY_RUN_CAUSE = {
  /** Nothing to surface: the run is unfinished or errored, or something scored. */
  NONE: "none",
  /** Nothing was generated beyond the baseline, and the baseline scored. */
  NO_CANDIDATES: "no-candidates",
  /**
   * Nothing produced a usable score: every non-baseline candidate is unscored,
   * or nothing scored at all. The OPIK-7029 "silent COMPLETED" gap.
   */
  SCORING_FAILED: "scoring-failed",
} as const;

export type EmptyRunCause =
  (typeof EMPTY_RUN_CAUSE)[keyof typeof EMPTY_RUN_CAUSE];

/**
 * Classifies a run that finished with nothing usable on screen, so the copy can
 * name the real cause.
 *
 * Order matters, because the checks overlap:
 *  1. Only COMPLETED runs qualify. ERROR is handled by RunErrorPanel, and
 *     in-progress runs legitimately have unscored candidates.
 *  2. No non-baseline candidates, baseline scored: NO_CANDIDATES.
 *  3. No non-baseline candidates, baseline unscored: SCORING_FAILED, since
 *     nothing was evaluated at all.
 *  4. Candidates exist but none scored: SCORING_FAILED.
 *
 * The baseline (stepIndex 0) never counts as optimizer output; a scored baseline
 * is expected on every run.
 */
export const computeEmptyRunCause = (
  candidates: AggregatedCandidate[],
  status?: OPTIMIZATION_STATUS,
): EmptyRunCause => {
  if (status !== OPTIMIZATION_STATUS.COMPLETED) return EMPTY_RUN_CAUSE.NONE;

  const nonBaselineCandidates = candidates.filter((c) => c.stepIndex !== 0);

  if (nonBaselineCandidates.length === 0) {
    const baselineScored = candidates.some(
      (c) => c.stepIndex === 0 && c.score != null,
    );
    return baselineScored
      ? EMPTY_RUN_CAUSE.NO_CANDIDATES
      : EMPTY_RUN_CAUSE.SCORING_FAILED;
  }

  return nonBaselineCandidates.every((c) => c.score == null)
    ? EMPTY_RUN_CAUSE.SCORING_FAILED
    : EMPTY_RUN_CAUSE.NONE;
};

/** Panel heading. Names the cause instead of always reporting missing scores. */
export const getEmptyRunTitle = (cause: EmptyRunCause): string =>
  cause === EMPTY_RUN_CAUSE.NO_CANDIDATES
    ? "No candidates generated"
    : "No usable scores";

/**
 * Body copy for NO_CANDIDATES. Carries no call to action on purpose: the metric
 * worked and the baseline was kept, so there is nothing to fix or retry.
 */
const NO_CANDIDATES_MESSAGE =
  "The optimizer produced no prompt variants to score, so the baseline prompt was kept. " +
  "This is common when the original prompt already scores well.";

/**
 * Body copy for the empty-run panel and the KPI score-card caption.
 *
 * NO_CANDIDATES has its own copy and ignores `scoring_health`: the baseline
 * scored, so per-item failure counts cannot explain it.
 *
 * SCORING_FAILED has two paths. When `scoring_health` is present with
 * `total_count > 0`, use the backend's exact counts (OPIK-7159 Wave 2),
 * distinguishing all-failed from partial and keeping the noun in agreement with
 * total_count. Otherwise return the static Wave-1 message unchanged.
 *
 * Returns null when there is nothing to say: cause NONE, or health data
 * reporting no failures.
 */
export const getEmptyRunMessage = (
  cause: EmptyRunCause,
  scoringHealth?: OptimizationScoringHealth,
): string | null => {
  if (cause === EMPTY_RUN_CAUSE.NONE) return null;

  if (cause === EMPTY_RUN_CAUSE.NO_CANDIDATES) return NO_CANDIDATES_MESSAGE;

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
 * Shortened version of {@link getEmptyRunMessage} for the KPI score-card
 * caption, where space is tight. Returns null under the same conditions: cause
 * NONE, or health data reporting no failures.
 *
 * The NO_CANDIDATES caption stays neutral, because the score on the card is the
 * baseline's real score rather than a failure.
 */
export const getEmptyRunKPICaption = (
  cause: EmptyRunCause,
  scoringHealth?: OptimizationScoringHealth,
): string | null => {
  if (cause === EMPTY_RUN_CAUSE.NONE) return null;

  if (cause === EMPTY_RUN_CAUSE.NO_CANDIDATES) {
    return "No candidates generated. Baseline prompt kept.";
  }

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
