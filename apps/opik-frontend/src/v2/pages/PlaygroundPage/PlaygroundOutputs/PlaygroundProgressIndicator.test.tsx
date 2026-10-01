import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen } from "@testing-library/react";

import { DATASET_TYPE } from "@/types/datasets";
import PlaygroundProgressIndicator from "./PlaygroundProgressIndicator";

let datasetType: DATASET_TYPE;
let progressPhase: string | null;

vi.mock("@/store/PlaygroundStore", () => ({
  useProgressTotal: () => 10,
  useProgressCompleted: () => 4,
  useProgressPhase: () => progressPhase,
  useDatasetType: () => datasetType,
}));

describe("PlaygroundProgressIndicator", () => {
  beforeEach(() => {
    datasetType = DATASET_TYPE.DATASET;
    progressPhase = "running";
  });

  it("numbers the phases for a test suite, which has a second one", () => {
    datasetType = DATASET_TYPE.TEST_SUITE;

    render(<PlaygroundProgressIndicator />);

    expect(screen.getByText("Step 1: Gathering LLM output")).toBeDefined();
  });

  it("does not promise a step 2 for a dataset run, which has no assertions", () => {
    render(<PlaygroundProgressIndicator />);

    expect(screen.getByText("Gathering LLM output")).toBeDefined();
    expect(screen.queryByText(/Step \d/)).toBeNull();
  });

  it("falls back to a neutral label for a phase it has no wording for", () => {
    progressPhase = "evaluating";

    render(<PlaygroundProgressIndicator />);

    expect(screen.getByText("Progress")).toBeDefined();
  });
});
