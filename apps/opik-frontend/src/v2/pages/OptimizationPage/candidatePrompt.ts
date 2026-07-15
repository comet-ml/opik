import get from "lodash/get";

import { Experiment } from "@/types/datasets";
import { AggregatedCandidate } from "@/types/optimizations";
import { ComparisonCandidate } from "@/shared/CodeDiff/promptComparisonTargets";

type CandidateWithExperiments = { experimentIds: string[] };

/**
 * Maps an optimization candidate to the minimal structural shape that
 * `buildPromptComparisonTargets` works with (it deliberately doesn't know
 * about `AggregatedCandidate`).
 */
export const toComparisonCandidate = (
  candidate: AggregatedCandidate,
): ComparisonCandidate => ({
  id: candidate.candidateId,
  stepIndex: candidate.stepIndex,
  parentCandidateIds: candidate.parentCandidateIds,
  trialNumber: candidate.trialNumber,
});

/**
 * Resolve a candidate's prompt from its trial experiment configuration. The
 * prompt lives on the experiment (not the candidate), under
 * `metadata.configuration.prompt` (or the legacy `prompt_messages`). Returns
 * the raw prompt value — PromptComparison normalizes it — or null when none of
 * the candidate's experiments carry one.
 */
export const getCandidatePrompt = (
  candidate: CandidateWithExperiments,
  experimentsById: Map<string, Experiment>,
): unknown => {
  for (const id of candidate.experimentIds) {
    const config = get(experimentsById.get(id)?.metadata, "configuration");
    if (config && typeof config === "object") {
      const record = config as Record<string, unknown>;
      const prompt = record.prompt ?? record.prompt_messages;
      // Skip an empty-string prompt: it would yield no diff rows and render a
      // blank section instead of the "No prompt available." fallback. Treat it
      // as absent so a later experiment (or the null fallback) is used.
      if (
        prompt != null &&
        !(typeof prompt === "string" && prompt.trim() === "")
      )
        return prompt;
    }
  }
  return null;
};
