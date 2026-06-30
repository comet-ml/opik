import React, { useMemo } from "react";
import { ArrowUpRight } from "lucide-react";

import { Button } from "@/ui/button";
import { AggregatedCandidate } from "@/types/optimizations";
import { Experiment } from "@/types/datasets";
import PromptComparison from "@/shared/CodeDiff/PromptComparison";
import {
  buildPromptComparisonTargets,
  ComparisonCandidate,
} from "@/shared/CodeDiff/promptComparisonTargets";
import { getCandidatePrompt } from "./candidatePrompt";

type BestTrialPromptProps = {
  bestCandidate: AggregatedCandidate;
  candidates: AggregatedCandidate[];
  experiments: Experiment[];
  onViewTrial?: () => void;
};

const toComparisonCandidate = (
  c: AggregatedCandidate,
): ComparisonCandidate => ({
  id: c.candidateId,
  stepIndex: c.stepIndex,
  parentCandidateIds: c.parentCandidateIds,
  trialNumber: c.trialNumber,
});

/**
 * Overview "Best trial prompt" panel: shows the best trial's prompt with an
 * inline diff against the baseline / parent (reuses the shared PromptComparison
 * + buildPromptComparisonTargets from the foundations PR).
 */
const BestTrialPrompt: React.FC<BestTrialPromptProps> = ({
  bestCandidate,
  candidates,
  experiments,
  onViewTrial,
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
    () => getCandidatePrompt(bestCandidate, experimentsById),
    [bestCandidate, experimentsById],
  );

  const targets = useMemo(
    () =>
      buildPromptComparisonTargets({
        candidate: toComparisonCandidate(bestCandidate),
        candidates: candidates.map(toComparisonCandidate),
        getPrompt: (cc) => {
          const original = candidatesById.get(cc.id);
          return original
            ? getCandidatePrompt(original, experimentsById)
            : null;
        },
      }),
    [bestCandidate, candidates, candidatesById, experimentsById],
  );

  if (current == null) return null;

  return (
    <div className="rounded-lg border bg-muted/20 p-6">
      <div className="mb-4 flex items-center justify-between">
        <h3 className="comet-title-s">Best trial prompt</h3>
        {onViewTrial && (
          <Button variant="ghost" size="sm" onClick={onViewTrial}>
            View trial
            <ArrowUpRight className="ml-1 size-3.5" />
          </Button>
        )}
      </div>
      <PromptComparison
        current={current}
        targets={targets}
        currentLabel="Best trial"
      />
    </div>
  );
};

export default BestTrialPrompt;
