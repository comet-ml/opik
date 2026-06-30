import { describe, it, expect, vi } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";

import BestTrialPrompt from "./BestTrialPrompt";
import { AggregatedCandidate } from "@/types/optimizations";
import { Experiment } from "@/types/datasets";

const makeExperiment = (id: string, prompt: unknown): Experiment =>
  ({ id, metadata: { configuration: { prompt } } }) as unknown as Experiment;

const makeCandidate = (
  candidateId: string,
  stepIndex: number,
  trialNumber: number,
  experimentIds: string[],
  parentCandidateIds: string[] = [],
): AggregatedCandidate =>
  ({
    id: candidateId,
    candidateId,
    stepIndex,
    trialNumber,
    experimentIds,
    parentCandidateIds,
  }) as unknown as AggregatedCandidate;

const baseline = makeCandidate("c0", 0, 1, ["e0"]);
const best = makeCandidate("c1", 2, 3, ["e1"], ["c0"]);

const experiments = [
  makeExperiment("e0", [{ role: "system", content: "baseline prompt" }]),
  makeExperiment("e1", [{ role: "system", content: "improved prompt" }]),
];

describe("BestTrialPrompt", () => {
  it("renders the renamed section with the best trial's prompt", () => {
    const { container } = render(
      <BestTrialPrompt
        bestCandidate={best}
        candidates={[baseline, best]}
        experiments={experiments}
      />,
    );

    expect(screen.getByText("Best trial prompt")).toBeInTheDocument();
    expect(container.textContent).toContain("improved prompt");
    // baseline target available as a diff target
    expect(screen.getByText("Baseline")).toBeInTheDocument();
    expect(screen.getByText("→ Best trial")).toBeInTheDocument();
  });

  it("fires onViewTrial when the view-trial control is clicked", () => {
    const onViewTrial = vi.fn();
    render(
      <BestTrialPrompt
        bestCandidate={best}
        candidates={[baseline, best]}
        experiments={experiments}
        onViewTrial={onViewTrial}
      />,
    );

    fireEvent.click(screen.getByText("View trial"));
    expect(onViewTrial).toHaveBeenCalledTimes(1);
  });

  it("renders nothing when the best trial has no resolvable prompt", () => {
    const { container } = render(
      <BestTrialPrompt
        bestCandidate={makeCandidate("c9", 1, 2, ["missing"])}
        candidates={[makeCandidate("c9", 1, 2, ["missing"])]}
        experiments={experiments}
      />,
    );
    expect(container).toBeEmptyDOMElement();
  });
});
