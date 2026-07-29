/**
 * Utility functions for optimization chart data processing
 */

import isNumber from "lodash/isNumber";

import { AggregatedCandidate } from "@/types/optimizations";
import { TagProps } from "@/ui/tag";
import {
  formatAsPercentage,
  formatAsDuration,
  formatAsCurrency,
} from "@/lib/optimization-formatters";

export type FeedbackScore = {
  name: string;
  value: number;
};

export type TrialStatus =
  | "baseline"
  | "passed"
  | "evaluating"
  | "pruned"
  | "running"
  // A trial that ran but produced no score — the metric/judge failed on it.
  // Distinct from "running" (which means "not scored *yet*"): a "failed" trial
  // is terminal. See computeCandidateStatuses for how this is derived.
  | "failed";

export const STATUS_VARIANT_MAP: Record<TrialStatus, TagProps["variant"]> = {
  baseline: "gray",
  passed: "blue",
  evaluating: "orange",
  pruned: "pink",
  running: "yellow",
  failed: "red",
};

// A fuchsia scale encodes trial status on the progress chart: baseline and
// passed share fuchsia-500, discarded is the lighter fuchsia-300, and the
// best trial is the darkest fuchsia-900. In-progress states (evaluating /
// running) stay orange / yellow so active work reads as distinct.
export const TRIAL_STATUS_COLORS: Record<TrialStatus, string> = {
  baseline: "var(--color-fuchsia)",
  passed: "var(--color-fuchsia)",
  evaluating: "var(--color-orange)",
  pruned: "var(--trial-pruned)",
  running: "var(--color-yellow)",
  failed: "var(--color-red)",
};

/** Best-trial dot colour — darkest in the fuchsia scale (theme-aware, see main.scss). */
export const TRIAL_BEST_COLOR = "var(--trial-best)";

/** Ring around the best-trial dot — the two-tone best marker (theme-aware, see main.scss). */
export const TRIAL_BEST_RING_COLOR = "var(--trial-best-ring)";

/**
 * Statuses a trial holds while its evaluation is still in flight. These are not
 * outcomes, so the chart must never paint them in an outcome colour — see
 * {@link getTrialDotColor} and the dataset-run legend.
 */
export const IN_PROGRESS_TRIAL_STATUSES: readonly TrialStatus[] = [
  "evaluating",
  "running",
] as const;

export const isInProgressTrialStatus = (status: TrialStatus): boolean =>
  IN_PROGRESS_TRIAL_STATUSES.includes(status);

/**
 * Statuses a dataset run shows in their own colour rather than collapsing into
 * an outcome — the in-progress pair plus "failed", which getTrialDotColor
 * already keeps red. Every one of these needs a legend entry when present, or
 * the chart carries a colour the legend does not explain.
 */
const DATASET_UNCOLLAPSED_STATUSES: readonly TrialStatus[] = [
  ...IN_PROGRESS_TRIAL_STATUSES,
  "failed",
] as const;

/**
 * Fill colour for a trial dot on the progress chart:
 * - the best trial always wins, in its own darkest fuchsia;
 * - test-suite runs colour every status (their legend distinguishes all states);
 * - dataset runs collapse *outcomes* to passed vs discarded, but keep the
 *   in-progress states in their own colours (see below).
 */
export const getTrialDotColor = ({
  status,
  isBest,
  isTestSuite,
}: {
  status: TrialStatus;
  isBest: boolean;
  isTestSuite?: boolean;
}): string => {
  if (isBest) return TRIAL_BEST_COLOR;
  if (isTestSuite) return TRIAL_STATUS_COLORS[status];
  // Dataset runs collapse most statuses to passed, but a failed trial must stay
  // red (it scored nothing — it is not a passing trial) and pruned stays faded.
  if (status === "pruned") return TRIAL_STATUS_COLORS.pruned;
  if (status === "failed") return TRIAL_STATUS_COLORS.failed;
  // An in-progress trial is not an outcome. Collapsing it into the solid
  // "passed" fuchsia made the chart assert a pass for a trial the trials table
  // simultaneously labelled "Evaluating" — the chart claimed a result that did
  // not exist yet. Keep the orange/yellow so both surfaces agree (OPIK-7460).
  if (isInProgressTrialStatus(status)) return TRIAL_STATUS_COLORS[status];
  return TRIAL_STATUS_COLORS.passed;
};

export const TRIAL_STATUS_LABELS: Record<TrialStatus, string> = {
  baseline: "Baseline",
  passed: "Passed",
  evaluating: "Evaluating",
  // Internal status key stays "pruned"; user-facing label is "Discarded".
  pruned: "Discarded",
  running: "Running",
  failed: "Failed",
};

export type TrialCardRow = { label: string; value: string };

export type TrialCardModel = {
  /** Header title, e.g. "Trial #20". */
  title: string;
  /** Header status label, e.g. "Passed" or "Best trial". */
  statusLabel: string;
  /** Fill colour of the header status dot. */
  dotColor: string;
  /** Ring colour around the dot for the best trial; undefined otherwise. */
  dotRingColor?: string;
  /** Metric rows (Score/Pass rate, then Latency and Runtime cost when present). */
  rows: TrialCardRow[];
};

/**
 * Builds the view model for a trial card (see {@link ./TrialCard}) from a
 * candidate + its computed status. Keeps all the label/colour/metric derivation
 * out of the component so it renders straight from this model and can be
 * unit-tested without the DOM.
 *
 * The score row shows a percentage; test-suite runs relabel it "Pass rate" and
 * append the passed/total fraction. Latency and cost rows are omitted when the
 * candidate has no value for them.
 *
 * The status label deliberately carries no step reference ("Passed", not
 * "Passed step 3"): trial numbers are the chart's one user-facing numbering
 * (see {@link buildStepTickLabels}), and quoting a second, zero-based sequence
 * next to "Trial #4" read as an off-by-one bug (OPIK-7589).
 */
export const buildTrialCardModel = ({
  candidate,
  status,
  isTestSuite,
  isBest,
}: {
  candidate: AggregatedCandidate;
  status: TrialStatus;
  isTestSuite?: boolean;
  isBest?: boolean;
}): TrialCardModel => {
  const percentage = isNumber(candidate.score)
    ? formatAsPercentage(candidate.score)
    : "-";
  const fraction =
    isTestSuite && isNumber(candidate.score) && candidate.totalCount > 0
      ? ` (${candidate.passedCount}/${candidate.totalCount})`
      : "";

  const rows: TrialCardRow[] = [
    {
      label: isTestSuite ? "Pass rate" : "Score",
      value: `${percentage}${fraction}`,
    },
  ];
  if (candidate.latencyP50 != null) {
    rows.push({
      label: "Latency",
      value: formatAsDuration(candidate.latencyP50),
    });
  }
  if (candidate.runtimeCost != null) {
    rows.push({
      label: "Runtime cost",
      value: formatAsCurrency(candidate.runtimeCost),
    });
  }

  return {
    title: `Trial #${candidate.trialNumber}`,
    statusLabel: isBest ? "Best trial" : TRIAL_STATUS_LABELS[status],
    dotColor: isBest ? TRIAL_BEST_COLOR : TRIAL_STATUS_COLORS[status],
    dotRingColor: isBest ? TRIAL_BEST_RING_COLOR : undefined,
    rows,
  };
};

export const TRIAL_STATUS_ORDER: readonly TrialStatus[] = [
  "baseline",
  "passed",
  "evaluating",
  "pruned",
  "running",
  "failed",
] as const;

export type CandidateDataPoint = {
  candidateId: string;
  stepIndex: number;
  /** 1-based creation-order number — the "Trial #N" identity used across the
   *  run view (table, sidebar, deep links). The baseline is Trial #1. */
  trialNumber: number;
  parentCandidateIds: string[];
  value: number | null;
  status: TrialStatus;
  name: string;
};

export type TrialLegendItem = { color: string; label: string };

/**
 * Legend for the progress chart, listing only statuses actually plotted.
 *
 * Test-suite runs distinguish every status, so the legend mirrors the full
 * status order. Dataset runs collapse outcomes to passed vs discarded — those
 * two are always listed, since the trend line is read against them — and then
 * add each uncollapsed status that is present, so no dot on the chart carries a
 * colour the legend leaves unexplained (OPIK-7460).
 *
 * Pure and exported so the mapping is unit-testable without mounting recharts.
 */
export const buildTrialLegendItems = (
  chartData: CandidateDataPoint[],
  isTestSuite?: boolean,
): TrialLegendItem[] => {
  const present = (s: TrialStatus) => chartData.some((d) => d.status === s);

  if (isTestSuite) {
    return TRIAL_STATUS_ORDER.filter(present).map((s) => ({
      color: TRIAL_STATUS_COLORS[s],
      label: TRIAL_STATUS_LABELS[s],
    }));
  }

  return [
    { color: TRIAL_STATUS_COLORS.passed, label: "Passed trial" },
    { color: TRIAL_STATUS_COLORS.pruned, label: "Discarded trial" },
    ...DATASET_UNCOLLAPSED_STATUSES.filter(present).map((s) => ({
      color: TRIAL_STATUS_COLORS[s],
      label: `${TRIAL_STATUS_LABELS[s]} trial`,
    })),
  ];
};

export type ParentChildEdge = {
  parentCandidateId: string;
  childCandidateId: string;
};

export type InProgressInfo = {
  candidateId: string;
  stepIndex: number;
  parentCandidateIds: string[];
};

type CandidateLookups = {
  hasChildren: Set<string>;
  parentSiblings: Map<string, string[]>;
  bestScore: number | undefined;
  bestCandidate: AggregatedCandidate | undefined;
  expectedItemCount: number;
};

/**
 * Number of evaluated items a *finished* full evaluation has in this run, used
 * as the denominator for "has this trial finished evaluating?".
 *
 * There is no planned/expected item count anywhere in the API: every count on
 * AggregatedCandidate is derived from rows that already exist in
 * `experiment_items`, so they report items **completed so far**, never items
 * planned (backend: `trace_count = count(DISTINCT ei.trace_id)`, and
 * `total_count` / `passed_count` come from the `pass_rate_agg` CTE over the same
 * table). `Experiment.status` is no help either — the SDK never sets it, so
 * trial experiments are created already marked "completed".
 *
 * What the run does give us is its own denominator. Every full evaluation in one
 * optimization scores the same item set: the baseline evaluates
 * `validation_dataset or dataset` capped at `n_samples`
 * (base_optimizer._select_evaluation_dataset) and GEPA builds its valset from
 * that same source and cap (gepa_optimizer's `val_source` / `val_plan`). So the
 * step-0 baseline's completed count is the count every other trial must reach.
 *
 * Deliberately baseline-derived rather than a `max()` over all candidates: a
 * candidate groups *all* its experiments and `aggregateExperimentMetrics` sums
 * their counts, so one double-counted candidate would inflate the denominator
 * and freeze every finished trial on "Evaluating". Under-estimating (the
 * baseline itself still running) only delays the gate, which is the safe
 * direction — step 0 is always labelled "baseline" and never pruned anyway.
 */
const getExpectedItemCount = (candidates: AggregatedCandidate[]): number => {
  let baseline: AggregatedCandidate | undefined;
  for (const c of candidates) {
    if (c.stepIndex !== 0) continue;
    if (!baseline || c.created_at < baseline.created_at) baseline = c;
  }
  return baseline?.totalDatasetItemCount ?? 0;
};

/**
 * True while a candidate's evaluation is still in flight — it has scored fewer
 * items than a full evaluation in this run covers. Its score is therefore a
 * partial average and must not be read as a final result.
 *
 * `0` denominators mean "unknown" (no baseline yet, or counts not reported) and
 * fail open, leaving the pre-existing behaviour untouched.
 */
const isStillEvaluating = (
  c: AggregatedCandidate,
  expectedItemCount: number,
): boolean =>
  expectedItemCount > 0 && c.totalDatasetItemCount < expectedItemCount;

/**
 * Candidates eligible to be "the best trial".
 *
 * A partial average is not a result, so it must not win — and must not become
 * the `bestScore` threshold other trials are pruned against. Without this
 * filter a candidate three items into its evaluation could top the run on an
 * easy sample, take the "Best trial" badge, and prune the genuinely-best
 * *completed* trial down to "Discarded" — reintroducing the very symptom the
 * status gate removes, sourced from a different row (OPIK-7460).
 *
 * Falls back to the unfiltered set when nothing has completed yet (or counts
 * are unknown), so a run never loses its best marker: with a scored baseline
 * the filtered set is non-empty by construction, since the baseline is what
 * defines the denominator.
 */
const getBestEligibleCandidates = (
  candidates: AggregatedCandidate[],
  expectedItemCount: number,
): AggregatedCandidate[] => {
  const completed = candidates.filter(
    (c) => !isStillEvaluating(c, expectedItemCount),
  );
  return completed.length ? completed : candidates;
};

/** Highest-scoring candidate, earliest-created winning a tie. */
const reduceBestCandidate = (
  pool: AggregatedCandidate[],
): AggregatedCandidate | undefined =>
  pool.reduce<AggregatedCandidate | undefined>((best, c) => {
    if (c.score == null) return best;
    if (!best || best.score == null) return c;
    if (c.score > best.score) return c;
    if (c.score === best.score && c.created_at < best.created_at) return c;
    return best;
  }, undefined);

/**
 * The run's best trial, ignoring trials that have not finished evaluating.
 *
 * Exported so the page-level "best trial" (badge, best-prompt panel,
 * improved-over-baseline) is derived exactly the same way as the chart's — these
 * were two independent reduces before, and only one of them applying the
 * completion filter would put the table and the header in disagreement.
 */
export const selectBestCandidate = (
  candidates: AggregatedCandidate[],
): AggregatedCandidate | undefined =>
  reduceBestCandidate(
    getBestEligibleCandidates(candidates, getExpectedItemCount(candidates)),
  );

const buildCandidateLookups = (
  candidates: AggregatedCandidate[],
  inProgressInfo?: InProgressInfo,
): CandidateLookups => {
  const hasChildren = new Set<string>();
  const parentSiblings = new Map<string, string[]>();

  // Topology is derived from every candidate — an unfinished trial still has a
  // real parent and real siblings.
  for (const c of candidates) {
    for (const pid of c.parentCandidateIds) {
      hasChildren.add(pid);
    }
    // Group siblings by shared parent IDs (not just step index)
    const parentKey = [...c.parentCandidateIds].sort().join(",");
    const siblings = parentSiblings.get(parentKey) ?? [];
    siblings.push(c.candidateId);
    parentSiblings.set(parentKey, siblings);
  }

  if (inProgressInfo) {
    for (const pid of inProgressInfo.parentCandidateIds) {
      hasChildren.add(pid);
    }
  }

  // Scores are not. bestScore is the threshold trials get pruned against, so
  // letting a partial average set it would prune finished trials against a
  // number that is not a result yet.
  const expectedItemCount = getExpectedItemCount(candidates);
  const bestPool = getBestEligibleCandidates(candidates, expectedItemCount);

  let bestScore: number | undefined;
  for (const c of bestPool) {
    if (c.score != null && (bestScore == null || c.score > bestScore)) {
      bestScore = c.score;
    }
  }

  return {
    hasChildren,
    parentSiblings,
    bestScore,
    bestCandidate: reduceBestCandidate(bestPool),
    expectedItemCount,
  };
};

const computeInProgressStatus = (
  c: AggregatedCandidate,
  lookups: CandidateLookups,
): TrialStatus => {
  const {
    hasChildren,
    parentSiblings,
    bestScore,
    bestCandidate,
    expectedItemCount,
  } = lookups;
  if (c.score == null) return "running";
  const isBest = bestCandidate?.candidateId === c.candidateId;

  if (isBest || hasChildren.has(c.candidateId)) return "passed";

  // A trial that has not finished its items is ineligible for "pruned"
  // (user-facing "Discarded") no matter how low its score looks: mid-evaluation
  // the score is a partial average over the items done so far, so a value below
  // the running best is not evidence the trial lost. Sits ahead of BOTH pruned
  // branches below — the score comparison and the sibling-progress one — because
  // an unfinished trial has no final result for either to judge (OPIK-7460).
  //
  // Trials with children or the current best are exempt above: children only
  // spawn from an accepted candidate, so those evaluations are already done.
  if (isStillEvaluating(c, expectedItemCount)) return "evaluating";

  if (bestScore != null && c.score < bestScore) return "pruned";

  const parentKey = [...c.parentCandidateIds].sort().join(",");
  const siblings = parentSiblings.get(parentKey) ?? [];
  const siblingHasChildren = siblings.some(
    (sid) => sid !== c.candidateId && hasChildren.has(sid),
  );
  return siblingHasChildren ? "pruned" : "evaluating";
};

const computeCompletedStatus = (
  c: AggregatedCandidate,
  ancestorSet: Set<string>,
  bestCandidate: AggregatedCandidate | undefined,
): TrialStatus => {
  const isBest = bestCandidate?.candidateId === c.candidateId;
  const isDescendant = ancestorSet.has(c.candidateId);
  return isDescendant || isBest ? "passed" : "pruned";
};

const buildAncestorSet = (
  candidates: AggregatedCandidate[],
  hasChildren: Set<string>,
): Set<string> => {
  const ancestorSet = new Set<string>();
  const parentOf = new Map<string, string[]>();
  for (const c of candidates) {
    for (const pid of c.parentCandidateIds) {
      const existing = parentOf.get(c.candidateId) ?? [];
      existing.push(pid);
      parentOf.set(c.candidateId, existing);
    }
  }
  const queue = [...hasChildren];
  for (const id of queue) {
    if (ancestorSet.has(id)) continue;
    ancestorSet.add(id);
    const parents = parentOf.get(id);
    if (parents) queue.push(...parents);
  }
  return ancestorSet;
};

/**
 * Compute status for each candidate.
 *
 * During optimization: baseline → running → evaluating → passed/pruned
 * After completion: baseline, passed (has descendants or best), pruned (rest)
 * Applies to both test-suite and dataset runs so discarded trials render as the
 * faded "pruned" dots (matching the legend).
 */
export const computeCandidateStatuses = (
  candidates: AggregatedCandidate[],
  // Status no longer depends on the run type — pruning applies to dataset runs
  // too — but the arg is kept so existing call sites stay unchanged.
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  isTestSuite = true,
  isInProgress = false,
  inProgressInfo?: InProgressInfo,
): Map<string, TrialStatus> => {
  const statusMap = new Map<string, TrialStatus>();
  if (!candidates.length) return statusMap;

  const lookups = buildCandidateLookups(candidates, inProgressInfo);
  const ancestorSet = isInProgress
    ? undefined
    : buildAncestorSet(candidates, lookups.hasChildren);

  for (const c of candidates) {
    if (c.stepIndex === 0) {
      statusMap.set(c.candidateId, "baseline");
    } else if (c.score == null) {
      // An unscored trial is "running" only while the run is still active. Once
      // the run is terminal an unscored trial never will be scored — the metric
      // failed on it — so it is "failed", not perpetually "running" (OPIK-7029).
      statusMap.set(c.candidateId, isInProgress ? "running" : "failed");
    } else if (isInProgress) {
      statusMap.set(c.candidateId, computeInProgressStatus(c, lookups));
    } else {
      statusMap.set(
        c.candidateId,
        computeCompletedStatus(c, ancestorSet!, lookups.bestCandidate),
      );
    }
  }

  return statusMap;
};

/**
 * Build scatter data points from aggregated candidates.
 * Each candidate becomes one dot on the chart.
 */
export const buildCandidateChartData = (
  candidates: AggregatedCandidate[],
  isTestSuite = true,
  isInProgress = false,
  inProgressInfo?: InProgressInfo,
): CandidateDataPoint[] => {
  const statusMap = computeCandidateStatuses(
    candidates,
    isTestSuite,
    isInProgress,
    inProgressInfo,
  );

  return candidates
    .slice()
    .sort(
      (a, b) =>
        a.stepIndex - b.stepIndex || a.created_at.localeCompare(b.created_at),
    )
    .map((c) => ({
      candidateId: c.candidateId,
      stepIndex: c.stepIndex,
      trialNumber: c.trialNumber,
      parentCandidateIds: c.parentCandidateIds,
      value: c.score ?? null,
      status: statusMap.get(c.candidateId) ?? "pruned",
      name: c.name,
    }));
};

/** Statuses the trend line may pass through — the winning progression. */
const TREND_LINE_STATUSES: ReadonlySet<TrialStatus> = new Set([
  "baseline",
  "passed",
]);

/**
 * Edges of the trend line: ONE continuous path connecting the best-scoring
 * baseline/passed trial of each step — baseline → step 1 winner → … .
 * Everything else (discarded, still-evaluating, non-winning passed
 * trials) renders as loose dots off the line, so the line never forks even
 * when a step has several passed trials. Steps without a scored winner are
 * skipped — the line bridges straight to the next step that has one.
 */
export const buildTrendLineEdges = (
  data: CandidateDataPoint[],
): ParentChildEdge[] => {
  const bestPerStep = new Map<number, CandidateDataPoint>();
  for (const point of data) {
    if (!TREND_LINE_STATUSES.has(point.status) || point.value == null) {
      continue;
    }
    const current = bestPerStep.get(point.stepIndex);
    if (!current || point.value > current.value!) {
      bestPerStep.set(point.stepIndex, point);
    }
  }

  const path = Array.from(bestPerStep.entries())
    .sort(([stepA], [stepB]) => stepA - stepB)
    .map(([, point]) => point);

  return path.slice(1).map((point, index) => ({
    parentCandidateId: path[index].candidateId,
    childCandidateId: point.candidateId,
  }));
};

/**
 * Unique step indices, sorted ascending. Accepts anything carrying a
 * `stepIndex` — both `AggregatedCandidate[]` and chart `CandidateDataPoint[]`.
 */
export const getUniqueSteps = (items: { stepIndex: number }[]): number[] => {
  const steps = new Set(items.map((item) => item.stepIndex));
  return Array.from(steps).sort((a, b) => a - b);
};

/**
 * X-axis tick labels for the progress chart, keyed by step index.
 *
 * The axis stays *positioned* by optimizer step — branching runs put several
 * sibling trials on one step, and they must stack on a single x — but steps
 * are an internal grouping the rest of the run view never leads with: the
 * trials table, the sidebar, deep links and the trial cards all identify a dot
 * as "Trial #N" (1-based, baseline = #1). Labelling the same dot "Step 3" on
 * the axis and "Trial #4" in its card read as an off-by-one bug (OPIK-7589),
 * so the ticks speak trial numbers too: a single-trial step is "Trial N", a
 * fan-out step is the range "Trials N–M" (trial numbers follow creation order,
 * so one step's trials are contiguous), and step 0 is "Baseline" — matching
 * the baseline card's own status label.
 *
 * `ghostStep` is the step of the candidate currently being evaluated (the
 * dashed ghost dot). It has no aggregated candidate yet, so it is numbered
 * after every plotted trial — as its own tick, or extending the range of the
 * step it joins.
 */
export const buildStepTickLabels = (
  chartData: CandidateDataPoint[],
  ghostStep?: number | null,
): Map<number, string> => {
  const rangeByStep = new Map<number, { min: number; max: number }>();
  let maxTrialNumber = 0;
  for (const d of chartData) {
    maxTrialNumber = Math.max(maxTrialNumber, d.trialNumber);
    const range = rangeByStep.get(d.stepIndex);
    if (range) {
      range.min = Math.min(range.min, d.trialNumber);
      range.max = Math.max(range.max, d.trialNumber);
    } else {
      rangeByStep.set(d.stepIndex, { min: d.trialNumber, max: d.trialNumber });
    }
  }

  if (ghostStep != null) {
    const ghostTrialNumber = maxTrialNumber + 1;
    const range = rangeByStep.get(ghostStep);
    if (range) {
      range.max = Math.max(range.max, ghostTrialNumber);
    } else {
      rangeByStep.set(ghostStep, {
        min: ghostTrialNumber,
        max: ghostTrialNumber,
      });
    }
  }

  const labels = new Map<number, string>();
  for (const [stepIndex, { min, max }] of rangeByStep) {
    labels.set(
      stepIndex,
      stepIndex === 0
        ? "Baseline"
        : min === max
          ? `Trial ${min}`
          : `Trials ${min}–${max}`,
    );
  }
  return labels;
};

export type DotHit = { candidateId: string; cx: number; cy: number };

/**
 * Nearest dot to (x, y) within `maxDistance` px, or null when none is close
 * enough. Powers the chart's single hover/click handler instead of overlapping
 * per-dot hit areas: proximity is unambiguous, so clustered dots can't fight
 * over the pointer. Ties resolve to the last match in iteration order, i.e. the
 * dot drawn on top.
 */
export const findNearestDot = (
  positions: Iterable<[string, { cx: number; cy: number }]>,
  x: number,
  y: number,
  maxDistance: number,
): DotHit | null => {
  let nearest: DotHit | null = null;
  let nearestDistSq = maxDistance * maxDistance;
  for (const [candidateId, pos] of positions) {
    const dx = pos.cx - x;
    const dy = pos.cy - y;
    const distSq = dx * dx + dy * dy;
    if (distSq <= nearestDistSq) {
      nearestDistSq = distSq;
      nearest = { candidateId, cx: pos.cx, cy: pos.cy };
    }
  }
  return nearest;
};

/** A dot position on the chart, in pixel space. */
export type ChartPoint = { cx: number; cy: number };

/**
 * SVG path for a connector between two dots: a cubic bezier with horizontal
 * control points, giving the smooth S-curve used for both the solid parent→child
 * edges and the dashed ghost edges.
 */
export const buildEdgePath = (from: ChartPoint, to: ChartPoint): string => {
  const midX = (from.cx + to.cx) / 2;
  return `M ${from.cx},${from.cy} C ${midX},${from.cy} ${midX},${to.cy} ${to.cx},${to.cy}`;
};

const MAIN_OBJECTIVE_COLOR = "var(--color-blue)";

const SECONDARY_SCORE_COLORS = [
  "var(--color-orange)",
  "var(--color-green)",
  "var(--color-purple)",
  "var(--color-pink)",
  "var(--color-turquoise)",
  "var(--color-yellow)",
  "var(--color-burgundy)",
];

export const generateDistinctColorMap = (
  mainObjective: string,
  secondaryScores: string[],
): Record<string, string> => {
  const colorMap: Record<string, string> = {};
  colorMap[mainObjective] = MAIN_OBJECTIVE_COLOR;

  const sortedSecondaryScores = [...secondaryScores].sort((a, b) =>
    a.localeCompare(b, undefined, { sensitivity: "base" }),
  );

  sortedSecondaryScores.forEach((scoreName, index) => {
    colorMap[scoreName] =
      SECONDARY_SCORE_COLORS[index % SECONDARY_SCORE_COLORS.length];
  });

  return colorMap;
};
