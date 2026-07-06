import React, { useState } from "react";
import { ArrowUpRight, GitCompare } from "lucide-react";

import GitCompareOff from "@/icons/git-compare-off.svg?react";
import { AggregatedCandidate } from "@/types/optimizations";
import { Experiment } from "@/types/datasets";
import PromptComparison from "@/shared/CodeDiff/PromptComparison";
import { usePromptComparisonTargets } from "./usePromptComparisonTargets";

type BestTrialPromptProps = {
  bestCandidate: AggregatedCandidate;
  candidates: AggregatedCandidate[];
  experiments: Experiment[];
  onViewTrial?: () => void;
};

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
  const { current, targets } = usePromptComparisonTargets(
    bestCandidate,
    candidates,
    experiments,
  );

  const [showDiff, setShowDiff] = useState(false);
  const hasTargets = targets.length > 0;

  if (current == null) return null;

  const actionClass =
    "comet-body-s inline-flex h-6 items-center gap-1 text-foreground transition-colors hover:text-primary-hover";

  return (
    <div className="flex flex-col gap-2 rounded-md border bg-background px-4 py-3">
      <div className="flex w-full items-center justify-between px-0.5">
        <div className="flex items-center gap-1">
          <span className="flex size-3 shrink-0 items-center justify-center">
            <span className="size-1.5 rounded-full bg-[var(--trial-best)] shadow-[0_0_0_1.125px_var(--trial-best-ring)]" />
          </span>
          <span className="comet-body-s-accented text-foreground">
            Best trial prompt
          </span>
        </div>
        <div className="flex items-center gap-1.5">
          {hasTargets && (
            <button
              type="button"
              className={actionClass}
              onClick={() => setShowDiff((prev) => !prev)}
            >
              {showDiff ? (
                <GitCompareOff className="size-3.5" />
              ) : (
                <GitCompare className="size-3.5" />
              )}
              {showDiff ? "Hide diff" : "Diff vs baseline"}
            </button>
          )}
          {hasTargets && onViewTrial && (
            <span className="mx-0.5 h-3 w-px shrink-0 bg-border" />
          )}
          {onViewTrial && (
            <button type="button" className={actionClass} onClick={onViewTrial}>
              View trial
              <ArrowUpRight className="size-3.5" />
            </button>
          )}
        </div>
      </div>
      <PromptComparison
        current={current}
        targets={targets}
        currentLabel="Best trial"
        showControls={false}
        diff={showDiff}
      />
    </div>
  );
};

export default BestTrialPrompt;
