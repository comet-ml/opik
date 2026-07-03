import React, { useMemo } from "react";

import { AggregatedCandidate } from "@/types/optimizations";
import { Experiment } from "@/types/datasets";
import PromptComparison from "@/shared/CodeDiff/PromptComparison";
import { buildPromptComparisonTargets } from "@/shared/CodeDiff/promptComparisonTargets";
import { getCandidatePrompt, toComparisonCandidate } from "./candidatePrompt";

type TrialPromptSectionProps = {
  /** The open trial's candidate; carries the lineage for the diff targets. */
  candidate?: AggregatedCandidate;
  /** Every candidate in the run — resolves the baseline + parent diff targets. */
  candidates: AggregatedCandidate[];
  /** Every experiment in the run — resolves each candidate's prompt. */
  experiments: Experiment[];
  /** Open straight into the diff view (e.g. from the table's diff button). */
  defaultDiff?: boolean;
};

/**
 * Prompt tab of the trial sidebar. A thin wrapper over the shared
 * {@link PromptComparison} surface — same "Trial prompt" / "[target] → Trial"
 * diff experience used by the overview best-prompt panel — with baseline and
 * parent targets resolved via {@link buildPromptComparisonTargets}.
 */
const TrialPromptSection: React.FC<TrialPromptSectionProps> = ({
  candidate,
  candidates,
  experiments,
  defaultDiff = false,
}) => {
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

  if (current == null) {
    return (
      <p className="comet-body-s py-8 text-center text-muted-slate">
        No prompt available.
      </p>
    );
  }

  return (
    <PromptComparison
      title="Trial prompt"
      current={current}
      targets={targets}
      currentLabel="Trial"
      defaultDiff={defaultDiff}
    />
  );
};

export default TrialPromptSection;
