import { Experiment } from "@/types/datasets";

/**
 * Resolve a trial's parent experiment for the prompt diff.
 *
 * GEPA v2 runs identify the parent explicitly via `parent_candidate_ids`
 * metadata (matched against other experiments' `candidate_id`). Older runs
 * fall back to the previous trial in chronological order.
 */
export const resolveParentExperiment = (
  experiment: Experiment | undefined,
  allExperiments: Experiment[],
): Experiment | undefined => {
  if (!experiment) return undefined;

  const meta = experiment.metadata as Record<string, unknown> | undefined;
  const parentCandidateIds = meta?.parent_candidate_ids as string[] | undefined;
  if (parentCandidateIds?.length) {
    const match = allExperiments.find((exp) => {
      const expMeta = exp.metadata as Record<string, unknown> | undefined;
      const candidateId = expMeta?.candidate_id as string | undefined;
      return candidateId && parentCandidateIds.includes(candidateId);
    });
    if (match) return match;
  }

  const sorted = allExperiments
    .slice()
    .sort((a, b) => a.created_at.localeCompare(b.created_at));
  const currentIndex = sorted.findIndex((exp) => exp.id === experiment.id);
  if (currentIndex > 0) {
    return sorted[currentIndex - 1];
  }

  return undefined;
};
