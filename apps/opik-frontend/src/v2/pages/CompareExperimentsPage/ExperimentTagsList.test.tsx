import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { TooltipProvider } from "@/ui/tooltip";
import PermissionsTestProvider from "@/testing/PermissionsTestProvider";
import { EXPERIMENT_TYPE, Experiment } from "@/types/datasets";
import ExperimentTagsList from "./ExperimentTagsList";

vi.mock("@/api/datasets/useExperimentUpdate", () => ({
  default: () => ({ mutate: vi.fn() }),
}));

const experiment: Experiment = {
  id: "exp-1",
  dataset_id: "dataset-1",
  dataset_name: "Dataset",
  type: EXPERIMENT_TYPE.REGULAR,
  status: "completed",
  name: "Experiment",
  tags: ["baseline"],
  trace_count: 0,
  created_at: "2026-09-01T00:00:00Z",
  last_updated_at: "2026-09-01T00:00:00Z",
};

const renderTags = (canCreateExperiments: boolean) =>
  render(
    <PermissionsTestProvider permissions={{ canCreateExperiments }}>
      <TooltipProvider>
        <ExperimentTagsList
          tags={["baseline"]}
          experiment={experiment}
          experimentId="exp-1"
        />
      </TooltipProvider>
    </PermissionsTestProvider>,
  );

describe("ExperimentTagsList", () => {
  it("lets users who can create experiments add tags", () => {
    renderTags(true);

    expect(screen.getByText("baseline")).toBeInTheDocument();
    expect(screen.getByTestId("add-tag-button")).toBeInTheDocument();
  });

  it("shows tags read-only without experiment create permission", () => {
    renderTags(false);

    expect(screen.getByText("baseline")).toBeInTheDocument();
    expect(screen.queryByTestId("add-tag-button")).toBeNull();
  });
});
