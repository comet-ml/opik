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

/** Why a COMPLETED run has nothing usable to show (OPIK-7029, OPIK-7458). */
export const EMPTY_RUN_CAUSE = {
  NONE: "none",
  NO_CANDIDATES: "no-candidates",
  SCORING_FAILED: "scoring-failed",
} as const;

export type EmptyRunCause =
  (typeof EMPTY_RUN_CAUSE)[keyof typeof EMPTY_RUN_CAUSE];

/**
 * Classifies a COMPLETED run that shows nothing usable, so the copy can name the
 * real cause. ERROR is RunErrorPanel's job, and in-progress runs legitimately
 * have unscored candidates. The baseline (stepIndex 0) never counts as optimizer
 * output, since a scored baseline is expected on every run.
 *
 * `candidates` is the page-1 load capped at MAX_EXPERIMENTS_LOADED and sorted by
 * created_at, so the baseline is always present. `scoring_health` cannot replace
 * it: those counts are per dataset item, so they cannot separate "generated
 * nothing" from "candidates failed to score" — the distinction this draws.
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

export const getEmptyRunTitle = (cause: EmptyRunCause): string =>
  cause === EMPTY_RUN_CAUSE.NO_CANDIDATES
    ? "No candidates generated"
    : "No usable scores";

/** No call to action on purpose: nothing is broken, so there is nothing to retry. */
const NO_CANDIDATES_MESSAGE =
  "The optimizer produced no prompt variants to score, so the baseline prompt was kept. " +
  "This is common when the original prompt already scores well.";

/**
 * Single-sourced lead sentence for a scoring failure (OPIK-7159 Wave 2), so the
 * panel body and the KPI caption cannot drift apart. `lead` carries no trailing
 * punctuation: callers append their own tail.
 */
type ScoringFailureSummary =
  | { kind: "suppressed" | "unknown"; lead?: never }
  | { kind: "all-failed" | "partial"; lead: string };

const summarizeScoringFailure = (
  scoringHealth?: OptimizationScoringHealth,
): ScoringFailureSummary => {
  if (!scoringHealth || scoringHealth.total_count <= 0)
    return { kind: "unknown" };

  const { failed_count, total_count } = scoringHealth;

  if (failed_count === 0) return { kind: "suppressed" };

  if (failed_count >= total_count) {
    // The noun agrees with total_count, so one item reads "The item …" not
    // "All 1 item …".
    return {
      kind: "all-failed",
      lead:
        total_count === 1
          ? "The item failed to score"
          : `All ${total_count} items failed to score`,
    };
  }

  // total_count >= 2 always holds here, so the noun is always plural.
  return {
    kind: "partial",
    lead: `${failed_count} of ${total_count} items failed to score`,
  };
};

/**
 * Body copy for the empty-run panel. Returns null when there is nothing to say:
 * cause NONE, or health data reporting no failures.
 */
export const getEmptyRunMessage = (
  cause: EmptyRunCause,
  scoringHealth?: OptimizationScoringHealth,
): string | null => {
  if (cause === EMPTY_RUN_CAUSE.NONE) return null;

  if (cause === EMPTY_RUN_CAUSE.NO_CANDIDATES) return NO_CANDIDATES_MESSAGE;

  const failure = summarizeScoringFailure(scoringHealth);

  switch (failure.kind) {
    case "suppressed":
      return null;
    case "all-failed":
      return (
        `${failure.lead}. ` +
        "The metric may have errored on every evaluation. " +
        "Open the logs, check the metric and model, then run it again."
      );
    case "partial":
      return (
        `${failure.lead}. ` +
        "Some evaluations did not produce a usable result. " +
        "Open the logs to see which items failed, then run it again."
      );
    case "unknown":
      // Heuristic fallback (Wave 1, no backend data).
      return "This run finished but produced no usable scores — the metric may have failed on every item. Open the logs, check the metric and model, then run it again.";
  }
};

/**
 * Shortened {@link getEmptyRunMessage} for the KPI score card, where space is
 * tight. Same null conditions. The NO_CANDIDATES caption stays neutral because
 * the score on the card is the baseline's real score, not a failure.
 */
export const getEmptyRunKPICaption = (
  cause: EmptyRunCause,
  scoringHealth?: OptimizationScoringHealth,
): string | null => {
  if (cause === EMPTY_RUN_CAUSE.NONE) return null;

  if (cause === EMPTY_RUN_CAUSE.NO_CANDIDATES) {
    return "No candidates generated. Baseline prompt kept.";
  }

  const failure = summarizeScoringFailure(scoringHealth);

  switch (failure.kind) {
    case "suppressed":
      return null;
    case "all-failed":
    case "partial":
      return `${failure.lead} — check the logs.`;
    case "unknown":
      // Heuristic fallback (Wave 1).
      return "No usable scores — check the logs.";
  }
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

/**
 * The candidate an open trial sidebar is showing, resolved from its URL params.
 *
 * Experiment ids win over `trialNumber`. Ids are stable identities and the
 * `trials` param is what opens the sidebar at all, so they are always present
 * and always right; a trial number is neither. This view stopped counting the
 * baseline as Trial #1 (OPIK-7589), so a link minted before that change carries
 * a number one higher than it means — trusting `trialNumber` first would make
 * `trials=[baselineId]&trialNumber=1` open the first candidate while the URL
 * names the baseline. Preferring ids resolves those links correctly rather than
 * off-by-one, and changes nothing for current links, where the two agree.
 *
 * `trialNumber` remains the fallback, for a candidate whose experiments are not
 * in the loaded page of results.
 */
export const findActiveTrialCandidate = (
  candidates: AggregatedCandidate[],
  experimentIds: string[],
  trialNumber: number | null | undefined,
): AggregatedCandidate | undefined =>
  candidates.find((c) =>
    c.experimentIds.some((id) => experimentIds.includes(id)),
  ) ??
  (trialNumber != null
    ? candidates.find((c) => c.trialNumber === trialNumber)
    : undefined);
