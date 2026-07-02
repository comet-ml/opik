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
  | "running";

export const STATUS_VARIANT_MAP: Record<TrialStatus, TagProps["variant"]> = {
  baseline: "gray",
  passed: "blue",
  evaluating: "orange",
  pruned: "pink",
  running: "yellow",
};

// Figma uses a fuchsia scale for trial status on the progress chart: baseline
// and passed share fuchsia-500, discarded is the lighter fuchsia-300, and the
// best trial is the darkest fuchsia-900. In-progress states (evaluating /
// running) stay orange / yellow so active work reads as distinct.
export const TRIAL_STATUS_COLORS: Record<TrialStatus, string> = {
  baseline: "var(--color-fuchsia)",
  passed: "var(--color-fuchsia)",
  evaluating: "var(--color-orange)",
  pruned: "var(--trial-pruned)",
  running: "var(--color-yellow)",
};

/** Best-trial dot colour — darkest in the fuchsia scale (theme-aware, see main.scss). */
export const TRIAL_BEST_COLOR = "var(--trial-best)";

/** Ring around the best-trial dot — Figma's two-tone best marker (theme-aware, see main.scss). */
export const TRIAL_BEST_RING_COLOR = "var(--trial-best-ring)";

/**
 * Fill colour for a trial dot on the progress chart:
 * - the best trial always wins, in its own darkest fuchsia;
 * - test-suite runs colour every status (their legend distinguishes all states);
 * - dataset runs only distinguish passed vs discarded, so every non-pruned
 *   status collapses to the solid "passed" colour.
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
  return status === "pruned"
    ? TRIAL_STATUS_COLORS.pruned
    : TRIAL_STATUS_COLORS.passed;
};

export const TRIAL_STATUS_LABELS: Record<TrialStatus, string> = {
  baseline: "Baseline",
  passed: "Passed",
  evaluating: "Evaluating",
  pruned: "Discarded",
  running: "Running",
};

/**
 * Full status label for the trial tooltip header, including the step it
 * happened at (Figma: "Passed step 1", "Discarded in step 2"). The baseline has
 * no step suffix, and the best trial is labelled separately by the caller.
 */
export const getTrialStatusLabel = (
  status: TrialStatus,
  stepIndex: number,
): string => {
  switch (status) {
    case "baseline":
      return "Baseline";
    case "passed":
      return `Passed step ${stepIndex}`;
    case "pruned":
      return `Discarded in step ${stepIndex}`;
    case "evaluating":
      return `Evaluating step ${stepIndex}`;
    case "running":
      return `Running step ${stepIndex}`;
    default:
      return TRIAL_STATUS_LABELS[status];
  }
};

export type TrialCardRow = { label: string; value: string };

export type TrialCardModel = {
  /** Header title, e.g. "Trial #20". */
  title: string;
  /** Header status label, e.g. "Passed step 1" or "Best trial". */
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
 */
export const buildTrialCardModel = ({
  candidate,
  status,
  stepIndex,
  isTestSuite,
  isBest,
}: {
  candidate: AggregatedCandidate;
  status: TrialStatus;
  stepIndex: number;
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
    statusLabel: isBest ? "Best trial" : getTrialStatusLabel(status, stepIndex),
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
] as const;

export type CandidateDataPoint = {
  candidateId: string;
  stepIndex: number;
  parentCandidateIds: string[];
  value: number | null;
  status: TrialStatus;
  name: string;
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
};

const buildCandidateLookups = (
  candidates: AggregatedCandidate[],
  inProgressInfo?: InProgressInfo,
): CandidateLookups => {
  const hasChildren = new Set<string>();
  const parentSiblings = new Map<string, string[]>();
  let bestScore: number | undefined;

  for (const c of candidates) {
    for (const pid of c.parentCandidateIds) {
      hasChildren.add(pid);
    }
    // Group siblings by shared parent IDs (not just step index)
    const parentKey = [...c.parentCandidateIds].sort().join(",");
    const siblings = parentSiblings.get(parentKey) ?? [];
    siblings.push(c.candidateId);
    parentSiblings.set(parentKey, siblings);

    if (c.score != null && (bestScore == null || c.score > bestScore)) {
      bestScore = c.score;
    }
  }

  if (inProgressInfo) {
    for (const pid of inProgressInfo.parentCandidateIds) {
      hasChildren.add(pid);
    }
  }

  const bestCandidate = candidates.reduce<AggregatedCandidate | undefined>(
    (best, c) => {
      if (c.score == null) return best;
      if (!best || best.score == null) return c;
      if (c.score > best.score) return c;
      if (c.score === best.score && c.created_at < best.created_at) return c;
      return best;
    },
    undefined,
  );

  return { hasChildren, parentSiblings, bestScore, bestCandidate };
};

const computeInProgressStatus = (
  c: AggregatedCandidate,
  lookups: CandidateLookups,
): TrialStatus => {
  const { hasChildren, parentSiblings, bestScore, bestCandidate } = lookups;
  if (c.score == null) return "running";
  const isBest = bestCandidate?.candidateId === c.candidateId;

  if (isBest || hasChildren.has(c.candidateId)) return "passed";
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
 * faded "pruned" dots (matching the legend + Figma).
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
      statusMap.set(c.candidateId, "running");
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
      parentCandidateIds: c.parentCandidateIds,
      value: c.score ?? null,
      status: statusMap.get(c.candidateId) ?? "pruned",
      name: c.name,
    }));
};

/**
 * Build parent-child edges from chart data.
 *
 * Only connects the winning progression: edges into a discarded (pruned) trial
 * are skipped so the line follows baseline → passed → best and the discarded
 * trials render as loose dots below it (matching Figma), instead of the line
 * diving down to every discarded child and back up.
 */
export const buildParentChildEdges = (
  data: CandidateDataPoint[],
): ParentChildEdge[] => {
  const candidateIds = new Set(data.map((d) => d.candidateId));
  const edges: ParentChildEdge[] = [];

  for (const point of data) {
    if (point.status === "pruned") continue;
    for (const parentId of point.parentCandidateIds) {
      if (candidateIds.has(parentId)) {
        edges.push({
          parentCandidateId: parentId,
          childCandidateId: point.candidateId,
        });
      }
    }
  }

  return edges;
};

/**
 * Get unique step indices from candidates, sorted.
 */
/**
 * Unique step indices, sorted ascending. Accepts anything carrying a
 * `stepIndex` — both `AggregatedCandidate[]` and chart `CandidateDataPoint[]`.
 */
export const getUniqueSteps = (items: { stepIndex: number }[]): number[] => {
  const steps = new Set(items.map((item) => item.stepIndex));
  return Array.from(steps).sort((a, b) => a - b);
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
