/**
 * Utility functions for optimization chart data processing
 */

import { AggregatedCandidate } from "@/types/optimizations";

export type FeedbackScore = {
  name: string;
  value: number;
};

export type TrialStatus = "baseline" | "passed" | "lost";

export const TRIAL_STATUS_COLORS: Record<TrialStatus, string> = {
  baseline: "var(--color-gray)",
  passed: "var(--color-blue)",
  lost: "var(--color-pink)",
};

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

/**
 * Compute status for each candidate:
 * - Step 0 candidates = "baseline"
 * - "lost" when a candidate has no children AND we're confident it won't
 *   get any: either the optimization is finished, or a candidate exists
 *   at step X+2 (meaning step X+1 has fully produced its generation)
 * - Otherwise "passed"
 */
export const computeCandidateStatuses = (
  candidates: AggregatedCandidate[],
  isOptimizationFinished = false,
): Map<string, TrialStatus> => {
  const statusMap = new Map<string, TrialStatus>();
  if (!candidates.length) return statusMap;

  const maxStep = Math.max(...candidates.map((c) => c.stepIndex));

  // Collect which steps exist
  const stepsWithCandidates = new Set(candidates.map((c) => c.stepIndex));

  // Build set of candidate IDs that are referenced as parents
  const referencedParents = new Set<string>();
  for (const c of candidates) {
    for (const pid of c.parentCandidateIds) {
      referencedParents.add(pid);
    }
  }

  for (const c of candidates) {
    if (c.stepIndex === 0) {
      statusMap.set(c.candidateId, "baseline");
    } else if (
      !referencedParents.has(c.candidateId) &&
      c.stepIndex < maxStep &&
      (isOptimizationFinished || stepsWithCandidates.has(c.stepIndex + 2))
    ) {
      statusMap.set(c.candidateId, "lost");
    } else {
      statusMap.set(c.candidateId, "passed");
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
  isOptimizationFinished = false,
): CandidateDataPoint[] => {
  const statusMap = computeCandidateStatuses(
    candidates,
    isOptimizationFinished,
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
      status: statusMap.get(c.candidateId) ?? "lost",
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
