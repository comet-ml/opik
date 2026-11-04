import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";

import { DATASET_TYPE } from "@/types/datasets";
import PlaygroundProgressIndicator from "./PlaygroundProgressIndicator";

let datasetType: DATASET_TYPE;
let progressPhase: string | null;
let watchLapsed: boolean;
let hasRunProgress: boolean;
let progressTotal: number;
const onCheckAgain = vi.fn();

vi.mock("@/store/PlaygroundStore", () => ({
  useProgressTotal: () => progressTotal,
  useProgressCompleted: () => 4,
  useProgressPhase: () => progressPhase,
  useDatasetType: () => datasetType,
  useIsAnyWatchLapsed: () => watchLapsed,
  useHasRunProgress: () => hasRunProgress,
  useShowsRunProgress: () => hasRunProgress || progressPhase !== null,
}));

describe("PlaygroundProgressIndicator", () => {
  beforeEach(() => {
    datasetType = DATASET_TYPE.DATASET;
    progressPhase = "running";
    watchLapsed = false;
    hasRunProgress = true;
    progressTotal = 10;
    onCheckAgain.mockClear();
  });

  // /execute pages the whole dataset before it answers with a total, so a large run spends seconds
  // with nothing to count. The bar's row is on the page throughout; leaving it blank read as a
  // glitch rather than as a run starting.
  describe("a run whose size is not known yet", () => {
    beforeEach(() => {
      hasRunProgress = false;
      progressTotal = 0;
      // A whole-run launch sets its phase at the press; the total follows when /execute answers.
      progressPhase = "running";
    });

    it("should show the bar rather than an empty space", () => {
      render(<PlaygroundProgressIndicator onCheckAgain={onCheckAgain} />);

      expect(screen.getByText("Starting run")).toBeInTheDocument();
      expect(
        screen.getByText("Preparing dataset items..."),
      ).toBeInTheDocument();
    });

    it("should not show a count it does not have", () => {
      render(<PlaygroundProgressIndicator onCheckAgain={onCheckAgain} />);

      expect(screen.queryByText(/completed/)).not.toBeInTheDocument();
      expect(screen.queryByText(/NaN/)).not.toBeInTheDocument();
    });

    // A single-prompt run reports itself per cell and never sets a total, so this bar is not its
    // to show. Keyed on the total alone it sat on "Starting run" for the whole run.
    it("should show nothing for a run that sets no phase, such as a scoped one", () => {
      progressPhase = null;

      const { container } = render(
        <PlaygroundProgressIndicator onCheckAgain={onCheckAgain} />,
      );

      expect(container).toBeEmptyDOMElement();
    });
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
    watchLapsed = true;

    render(<PlaygroundProgressIndicator onCheckAgain={onCheckAgain} />);

    expect(screen.queryByText(/4\/10 completed/)).toBeNull();
    expect(
      screen.getByText(/Still running on the server, refresh to update/),
    ).toBeDefined();
    expect(screen.getByRole("button", { name: "Refresh" })).toBeDefined();
  });

  it("asks to pick the run back up when Refresh is pressed", () => {
    watchLapsed = true;

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
