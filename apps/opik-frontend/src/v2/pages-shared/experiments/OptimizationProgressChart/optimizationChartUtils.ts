/**
 * Utility functions for optimization chart data processing
 */

import { AggregatedCandidate } from "@/types/optimizations";
import { TagProps } from "@/ui/tag";

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

export const TRIAL_STATUS_COLORS: Record<TrialStatus, string> = {
  baseline: "var(--color-gray)",
  passed: "var(--color-blue)",
  evaluating: "var(--color-orange)",
  pruned: "var(--color-pink)",
  running: "var(--color-yellow)",
};

export const TRIAL_STATUS_LABELS: Record<TrialStatus, string> = {
  baseline: "Baseline",
  passed: "Passed",
  evaluating: "Evaluating",
  pruned: "Pruned",
  running: "Running",
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
 * Non-evaluation-suite: all scored = passed (no pruning)
 */
export const computeCandidateStatuses = (
  candidates: AggregatedCandidate[],
  isEvaluationSuite = true,
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
    } else if (!isEvaluationSuite) {
      statusMap.set(c.candidateId, c.score == null ? "running" : "passed");
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
  isEvaluationSuite = true,
  isInProgress = false,
  inProgressInfo?: InProgressInfo,
): CandidateDataPoint[] => {
  const statusMap = computeCandidateStatuses(
    candidates,
    isEvaluationSuite,
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
 */
export const buildParentChildEdges = (
  data: CandidateDataPoint[],
): ParentChildEdge[] => {
  const candidateIds = new Set(data.map((d) => d.candidateId));
  const edges: ParentChildEdge[] = [];

  for (const point of data) {
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
export const getUniqueSteps = (candidates: AggregatedCandidate[]): number[] => {
  const steps = new Set(candidates.map((c) => c.stepIndex));
  return Array.from(steps).sort((a, b) => a - b);
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
