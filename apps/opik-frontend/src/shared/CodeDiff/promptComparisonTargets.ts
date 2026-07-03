/**
 * Shared helpers for the {@link ./PromptComparison} component.
 *
 * Optimization trials are compared against either the run's baseline (the
 * step-0 candidate) or the candidate's direct parent(s). This module turns a
 * candidate + the full candidate list into the list of comparison targets the
 * UI offers in its "Compare against" control, so the three surfaces that render
 * a prompt diff (overview best-prompt panel, trials prompt cell, trial sidebar)
 * resolve targets the same way.
 */

export type PromptComparisonTarget = {
  /** Stable id of the target candidate (used as the Select value). */
  id: string;
  /** Human-readable label, e.g. "Baseline" or "Parent (Trial #3)". */
  label: string;
  /** The prompt to diff the current prompt against. */
  prompt: unknown;
};

/**
 * Minimal shape needed to resolve comparison targets. Kept structural (rather
 * than importing `AggregatedCandidate`) so the helper stays pure and trivially
 * testable, and so callers can pass any candidate-like row.
 */
export type ComparisonCandidate = {
  id: string;
  /** 0 marks the baseline candidate. */
  stepIndex: number;
  /** Ids of the candidates this one was derived from. */
  parentCandidateIds: string[];
  /** 1-indexed trial number, used to label parent targets. */
  trialNumber: number;
};

export const BASELINE_TARGET_LABEL = "Baseline";
export const PARENT_TARGET_LABEL = "Parent";

/**
 * A candidate usually has a single parent, shown simply as "Parent".
 * Evolutionary crossover can produce multiple parents, so we disambiguate
 * those by trial number — otherwise the dropdown would list two identical
 * "Parent" options.
 */
export const buildParentTargetLabel = (trialNumber: number): string =>
  `${PARENT_TARGET_LABEL} (Trial #${trialNumber})`;

type BuildTargetsParams<T extends ComparisonCandidate> = {
  /** The candidate whose prompt is being compared. */
  candidate: T;
  /** Every candidate in the run (used to look up baseline + parents). */
  candidates: T[];
  /**
   * Resolves a candidate to its prompt. The prompt does not live on the
   * candidate itself (it comes from the trial experiment), so the caller
   * supplies the lookup. Targets whose prompt resolves to `null`/`undefined`
   * are skipped — there is nothing meaningful to diff against.
   */
  getPrompt: (candidate: T) => unknown;
};

/**
 * Builds the ordered list of comparison targets for a candidate: the baseline
 * first (when it isn't the candidate itself), then each resolvable parent.
 *
 * The candidate itself, duplicates, missing parents, and targets without a
 * resolvable prompt are all excluded. Returns an empty array when there is
 * nothing to compare against (e.g. the candidate IS the baseline).
 */
export const buildPromptComparisonTargets = <T extends ComparisonCandidate>({
  candidate,
  candidates,
  getPrompt,
}: BuildTargetsParams<T>): PromptComparisonTarget[] => {
  const byId = new Map(candidates.map((c) => [c.id, c]));
  const targets: PromptComparisonTarget[] = [];
  const seen = new Set<string>();

  const pushTarget = (target: T, label: string) => {
    if (target.id === candidate.id || seen.has(target.id)) return;
    const prompt = getPrompt(target);
    if (prompt === null || prompt === undefined) return;
    seen.add(target.id);
    targets.push({ id: target.id, label, prompt });
  };

  const baseline = candidates.find((c) => c.stepIndex === 0);
  if (baseline) {
    pushTarget(baseline, BASELINE_TARGET_LABEL);
  }

  const hasMultipleParents = candidate.parentCandidateIds.length > 1;
  candidate.parentCandidateIds.forEach((parentId) => {
    const parent = byId.get(parentId);
    if (parent) {
      pushTarget(
        parent,
        hasMultipleParents
          ? buildParentTargetLabel(parent.trialNumber)
          : PARENT_TARGET_LABEL,
      );
    }
  });

  return targets;
};
