import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";

import { DATASET_TYPE } from "@/types/datasets";
import PlaygroundProgressIndicator from "./PlaygroundProgressIndicator";

let datasetType: DATASET_TYPE;
let progressPhase: string | null;
let watchPaused: boolean;
const onCheckAgain = vi.fn();

vi.mock("@/store/PlaygroundStore", () => ({
  useProgressTotal: () => 10,
  useProgressCompleted: () => 4,
  useProgressPhase: () => progressPhase,
  useDatasetType: () => datasetType,
  useWatchPaused: () => watchPaused,
}));

describe("PlaygroundProgressIndicator", () => {
  beforeEach(() => {
    datasetType = DATASET_TYPE.DATASET;
    progressPhase = "running";
    watchPaused = false;
    onCheckAgain.mockClear();
  });

  it("numbers the phases for a test suite, which has a second one", () => {
    datasetType = DATASET_TYPE.TEST_SUITE;

    render(<PlaygroundProgressIndicator onCheckAgain={onCheckAgain} />);

    expect(screen.getByText("Step 1: Gathering LLM output")).toBeDefined();
  });

  it("does not promise a step 2 for a dataset run, which has no assertions", () => {
    render(<PlaygroundProgressIndicator onCheckAgain={onCheckAgain} />);

    expect(screen.getByText("Gathering LLM output")).toBeDefined();
    expect(screen.queryByText(/Step \d/)).toBeNull();
  });

  it("falls back to a neutral label for a phase it has no wording for", () => {
    progressPhase = "evaluating";

    render(<PlaygroundProgressIndicator onCheckAgain={onCheckAgain} />);

    expect(screen.getByText("Progress")).toBeDefined();
  });

  it("replaces the count with the way back once this tab stops following", () => {
    watchPaused = true;

    render(<PlaygroundProgressIndicator onCheckAgain={onCheckAgain} />);

    expect(screen.queryByText(/4\/10 completed/)).toBeNull();
    expect(
      screen.getByText(/Still running on the server, refresh to update/),
    ).toBeDefined();
    expect(screen.getByRole("button", { name: "Refresh" })).toBeDefined();
  });

  it("asks to pick the run back up when Refresh is pressed", () => {
    watchPaused = true;

    render(<PlaygroundProgressIndicator onCheckAgain={onCheckAgain} />);
    fireEvent.click(screen.getByRole("button", { name: "Refresh" }));

    expect(onCheckAgain).toHaveBeenCalledTimes(1);
  });

  it("shows the count while the run is still being followed", () => {
    render(<PlaygroundProgressIndicator onCheckAgain={onCheckAgain} />);

    expect(screen.getByText(/4\/10 completed/)).toBeDefined();
    expect(screen.queryByRole("button", { name: "Refresh" })).toBeNull();
  });
});
