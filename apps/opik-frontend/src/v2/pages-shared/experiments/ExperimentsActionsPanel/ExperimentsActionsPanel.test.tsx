import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import { TooltipProvider } from "@/ui/tooltip";
import { Experiment } from "@/types/datasets";
import ExperimentsActionsPanel from "./ExperimentsActionsPanel";

const mockNavigate = vi.fn();
vi.mock("@tanstack/react-router", () => ({
  useNavigate: () => mockNavigate,
}));

vi.mock("@/store/AppStore", () => ({
  default: vi.fn((selector) =>
    selector({
      activeWorkspaceName: "test-workspace",
    }),
  ),
  useActiveProjectId: () => "test-project-id",
}));

vi.mock("@/api/datasets/useExperimentBatchDeleteMutation", () => ({
  default: () => ({ mutate: vi.fn() }),
}));

vi.mock("@/api/datasets/useExperimentBatchUpdateMutation", () => ({
  default: () => ({ mutateAsync: vi.fn() }),
}));

const buildExperiment = (
  id: string,
  datasetId = "dataset-1",
  name = `Experiment ${id}`,
) =>
  ({
    id,
    name,
    dataset_id: datasetId,
    dataset_name: "Dataset",
    created_at: "2026-09-01T00:00:00Z",
  }) as Experiment;

const mockUseExperimentsList = vi.fn();
vi.mock("@/api/datasets/useExperimentsList", () => ({
  default: (...args: unknown[]) => mockUseExperimentsList(...args),
}));

const renderPanel = (experiments: Experiment[]) =>
  render(
    <TooltipProvider>
      <ExperimentsActionsPanel experiments={experiments} />
    </TooltipProvider>,
  );

const getCompareButton = () => screen.getByRole("button", { name: "Compare" });

describe("ExperimentsActionsPanel Compare button", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockUseExperimentsList.mockReturnValue({
      data: {
        content: [buildExperiment("exp-1"), buildExperiment("exp-2")],
        total: 2,
      },
      isPending: false,
    });
  });

  it("is disabled when no experiment is selected", () => {
    renderPanel([]);

    expect(getCompareButton()).toBeDisabled();
  });

  it("opens the comparable-experiments picker when one experiment is selected", () => {
    renderPanel([buildExperiment("exp-1")]);

    fireEvent.click(getCompareButton());

    expect(mockNavigate).not.toHaveBeenCalled();
    expect(
      screen.getByRole("dialog", { name: "Compare experiments" }),
    ).toBeInTheDocument();
    expect(mockUseExperimentsList).toHaveBeenLastCalledWith(
      expect.objectContaining({
        filters: [expect.objectContaining({ value: "dataset-1" })],
      }),
      expect.objectContaining({ enabled: true }),
    );
  });

  it("navigates to the Compare page with both experiments after picking one", () => {
    renderPanel([buildExperiment("exp-1")]);

    fireEvent.click(getCompareButton());
    fireEvent.click(screen.getByText("Experiment exp-2"));
    fireEvent.click(
      screen.getByRole("button", { name: "Compare 2 experiments" }),
    );

    expect(mockNavigate).toHaveBeenCalledWith(
      expect.objectContaining({
        params: expect.objectContaining({ datasetId: "dataset-1" }),
        search: { experiments: ["exp-1", "exp-2"] },
      }),
    );
  });

  it("navigates straight to the Compare page when two or more experiments are selected", () => {
    renderPanel([
      buildExperiment("exp-1"),
      buildExperiment("exp-2"),
      buildExperiment("exp-3"),
    ]);

    fireEvent.click(getCompareButton());

    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(mockNavigate).toHaveBeenCalledWith(
      expect.objectContaining({
        params: expect.objectContaining({ datasetId: "dataset-1" }),
        search: { experiments: ["exp-1", "exp-2", "exp-3"] },
      }),
    );
  });
});
