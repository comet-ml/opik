import React from "react";
import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import { TooltipProvider } from "@/ui/tooltip";
import { EVALUATION_METHOD, Experiment } from "@/types/datasets";
import PermissionsTestProvider from "@/testing/PermissionsTestProvider";
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

const renderPanel = (
  experiments: Experiment[],
  canCreateExperiments = true,
  props: Partial<React.ComponentProps<typeof ExperimentsActionsPanel>> = {},
) =>
  render(
    <PermissionsTestProvider permissions={{ canCreateExperiments }}>
      <TooltipProvider>
        <ExperimentsActionsPanel experiments={experiments} {...props} />
      </TooltipProvider>
    </PermissionsTestProvider>,
  );

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

const getCompareButton = () => screen.getByRole("button", { name: "Compare" });

describe("ExperimentsActionsPanel Compare button", () => {
  it("is disabled when no experiment is selected", () => {
    renderPanel([]);

    expect(getCompareButton()).toBeDisabled();
  });

  it("opens the comparable-experiments picker when one experiment is selected", () => {
    renderPanel([buildExperiment("exp-1")]);

    fireEvent.click(getCompareButton());

    expect(mockNavigate).not.toHaveBeenCalled();
    expect(
      screen.getByRole("dialog", { name: "Add to compare" }),
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

describe("ExperimentsActionsPanel mixed item sources", () => {
  it("disables Compare when the selection mixes item sources", () => {
    renderPanel([
      buildExperiment("exp-1", "dataset-1"),
      buildExperiment("exp-2", "dataset-2"),
    ]);

    expect(getCompareButton()).toBeDisabled();
  });

  it.each([
    [EVALUATION_METHOD.TEST_SUITE, EVALUATION_METHOD.TEST_SUITE, "test suite"],
    [EVALUATION_METHOD.DATASET, EVALUATION_METHOD.DATASET, "dataset"],
    [undefined, EVALUATION_METHOD.DATASET, "dataset"],
    [EVALUATION_METHOD.DATASET, EVALUATION_METHOD.TEST_SUITE, "item source"],
    [EVALUATION_METHOD.TEST_SUITE, undefined, "item source"],
  ])(
    "names the item source type in the tooltip (%s + %s → %s)",
    async (firstMethod, secondMethod, label) => {
      renderPanel([
        {
          ...buildExperiment("exp-1", "dataset-1"),
          evaluation_method: firstMethod,
        },
        {
          ...buildExperiment("exp-2", "dataset-2"),
          evaluation_method: secondMethod,
        },
      ]);

      fireEvent.focus(getCompareButton().parentElement!);

      expect(
        await screen.findAllByText(
          `Select experiments from the same ${label} to compare them`,
        ),
      ).not.toHaveLength(0);
    },
  );

  it("keeps tag and delete enabled for a mixed selection", () => {
    renderPanel([
      buildExperiment("exp-1", "dataset-1"),
      buildExperiment("exp-2", "dataset-2"),
    ]);

    expect(screen.getByRole("button", { name: "Manage tags" })).toBeEnabled();
    expect(screen.getByRole("button", { name: "Delete" })).toBeEnabled();
  });
});

describe("ExperimentsActionsPanel selection bar variant", () => {
  it("labels the Manage tags button and hides the compare explainer", () => {
    renderPanel([buildExperiment("exp-1")], true, {
      buttonVariant: "ghostInverted",
      buttonSize: "2xs",
    });

    expect(
      screen.getByRole("button", { name: "Manage tags" }),
    ).toHaveTextContent("Manage tags");
    expect(getCompareButton()).toBeEnabled();
  });
});

describe("ExperimentsActionsPanel permissions", () => {
  it("shows the tag and delete buttons when the user can create experiments", () => {
    renderPanel([buildExperiment("exp-1")]);

    expect(
      screen.getByRole("button", { name: "Manage tags" }),
    ).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Delete" })).toBeInTheDocument();
  });

  it("hides the tag and delete buttons but keeps Compare without experiment create permission", () => {
    renderPanel([buildExperiment("exp-1")], false);

    expect(screen.queryByRole("button", { name: "Manage tags" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Delete" })).toBeNull();
    expect(getCompareButton()).toBeInTheDocument();
  });
});
