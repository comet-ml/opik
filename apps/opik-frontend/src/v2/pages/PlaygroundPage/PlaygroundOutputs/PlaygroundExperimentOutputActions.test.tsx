import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen } from "@testing-library/react";

import PlaygroundExperimentOutputActions from "./PlaygroundExperimentOutputActions";

let isRunning: boolean;
let showsRunProgress: boolean;

vi.mock("@/store/PlaygroundStore", () => ({
  useIsRunning: () => isRunning,
  useShowsRunProgress: () => showsRunProgress,
}));

vi.mock(
  "@/v2/pages/PlaygroundPage/PlaygroundOutputs/PlaygroundProgressIndicator",
  () => ({ default: () => <div data-testid="progress-bar" /> }),
);

vi.mock(
  "@/v2/pages/PlaygroundPage/PlaygroundOutputs/PlaygroundExperimentName",
  () => ({ default: () => <div data-testid="experiment-name" /> }),
);

vi.mock("@/shared/DataTablePagination/DataTablePagination", () => ({
  default: ({ disabled }: { disabled?: boolean }) => (
    <div data-testid="pagination" data-disabled={String(Boolean(disabled))} />
  ),
}));

const renderActions = (datasetId: string | null = "dataset-1") =>
  render(
    <PlaygroundExperimentOutputActions
      datasetId={datasetId}
      page={1}
      onChangePage={vi.fn()}
      size={10}
      onChangeSize={vi.fn()}
      total={100}
      checkAgain={vi.fn()}
    />,
  );

beforeEach(() => {
  isRunning = false;
  showsRunProgress = false;
});

describe("PlaygroundExperimentOutputActions", () => {
  it("should show the pagination while nothing is running", () => {
    renderActions();

    expect(screen.getByTestId("pagination")).toBeInTheDocument();
    expect(screen.queryByTestId("progress-bar")).not.toBeInTheDocument();
  });

  it("should show the bar for a run that has one", () => {
    isRunning = true;
    showsRunProgress = true;

    renderActions();

    expect(screen.getByTestId("progress-bar")).toBeInTheDocument();
    expect(screen.queryByTestId("pagination")).not.toBeInTheDocument();
  });

  // A single-prompt run reports itself per cell and has no run-wide bar. The row still carries
  // the experiment name and the pagination, so it stays — what must not render is the padded
  // wrapper with nothing in it, which read as the bar failing to draw.
  it("should keep the name row for a run with no bar of its own", () => {
    isRunning = true;
    showsRunProgress = false;

    renderActions();

    expect(screen.getByTestId("experiment-name")).toBeInTheDocument();
    expect(screen.queryByTestId("progress-bar")).not.toBeInTheDocument();
    // Only one prompt is rewriting its column, so the rest of the results are stable to page
    // through.
    expect(screen.getByTestId("pagination")).toHaveAttribute(
      "data-disabled",
      "false",
    );
  });

  it("should render nothing outside experiment mode", () => {
    const { container } = renderActions(null);

    expect(container).toBeEmptyDOMElement();
  });
});
