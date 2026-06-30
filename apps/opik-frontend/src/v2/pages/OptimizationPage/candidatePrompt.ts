import get from "lodash/get";

import { Experiment } from "@/types/datasets";

type CandidateWithExperiments = { experimentIds: string[] };

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
      if (prompt != null) return prompt;
    }
  }
  return null;
};
