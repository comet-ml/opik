import { useMemo } from "react";

import { AggregatedCandidate } from "@/types/optimizations";
import { Experiment } from "@/types/datasets";
import {
  buildPromptComparisonTargets,
  PromptComparisonTarget,
} from "@/shared/CodeDiff/promptComparisonTargets";
import { getCandidatePrompt, toComparisonCandidate } from "./candidatePrompt";

type UsePromptComparisonTargetsResult = {
  /** The candidate's own prompt, or `null` when it can't be resolved. */
  current: unknown;
  /** Baseline + parent prompts to diff `current` against. */
  targets: PromptComparisonTarget[];
};

/**
 * Resolves a candidate's prompt plus its baseline/parent comparison targets from
 * the run's candidates and experiments. Shared by the overview "Best trial
 * prompt" panel and the trial sidebar's Prompt tab so prompt/target resolution
 * lives in one place (see {@link buildPromptComparisonTargets}).
 *
 * `candidate` is optional: when omitted (no trial selected) `current` is `null`
 * and `targets` is empty.
 */
export const usePromptComparisonTargets = (
  candidate: AggregatedCandidate | undefined,
  candidates: AggregatedCandidate[],
  experiments: Experiment[],
): UsePromptComparisonTargetsResult => {
  const experimentsById = useMemo(
    () => new Map(experiments.map((e) => [e.id, e])),
    [experiments],
  );
  const candidatesById = useMemo(
    () => new Map(candidates.map((c) => [c.candidateId, c])),
    [candidates],
  );

  const current = useMemo(
    () => (candidate ? getCandidatePrompt(candidate, experimentsById) : null),
    [candidate, experimentsById],
  );

  const targets = useMemo(() => {
    if (!candidate) return [];
    return buildPromptComparisonTargets({
      candidate: toComparisonCandidate(candidate),
      candidates: candidates.map(toComparisonCandidate),
      getPrompt: (cc) => {
        const original = candidatesById.get(cc.id);
        return original ? getCandidatePrompt(original, experimentsById) : null;
      },
    });
  }, [candidate, candidates, candidatesById, experimentsById]);

  return { current, targets };
};
