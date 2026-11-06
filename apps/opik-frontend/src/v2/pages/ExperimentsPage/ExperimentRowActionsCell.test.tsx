import { describe, it, expect, vi } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import { CellContext } from "@tanstack/react-table";
import { PermissionsProvider } from "@/contexts/PermissionsContext";
import { DEFAULT_PERMISSIONS } from "@/types/permissions";
import { GroupedExperiment } from "@/hooks/useGroupedExperimentsList";
import ExperimentRowActionsCell from "./ExperimentRowActionsCell";

vi.mock("@/api/datasets/useExperimentBatchDeleteMutation", () => ({
  default: () => ({ mutate: vi.fn() }),
}));
vi.mock("@/api/datasets/useExperimentUpdate", () => ({
  default: () => ({ mutate: vi.fn() }),
}));
vi.mock("@/shared/UpdateExperimentDialog/UpdateExperimentDialog", () => ({
  UpdateExperimentDialog: () => null,
}));

const context = {
  row: { original: { id: "exp-1", name: "Experiment", metadata: {} } },
  column: { columnDef: { meta: undefined } },
  table: { options: { meta: undefined } },
} as unknown as CellContext<GroupedExperiment, unknown>;

const renderCell = (canCreateExperiments: boolean) =>
  render(
    <PermissionsProvider
      value={{
        ...DEFAULT_PERMISSIONS,
        permissions: {
          ...DEFAULT_PERMISSIONS.permissions,
          canCreateExperiments,
        },
      }}
    >
      <ExperimentRowActionsCell {...context} />
    </PermissionsProvider>,
  );

describe("ExperimentRowActionsCell", () => {
  it("offers Edit and Delete when the user can create experiments", () => {
    renderCell(true);

    fireEvent.keyDown(screen.getByRole("button", { name: "Actions menu" }), {
      key: "Enter",
    });

    expect(screen.getByRole("menuitem", { name: "Edit" })).toBeInTheDocument();
    expect(
      screen.getByRole("menuitem", { name: "Delete" }),
    ).toBeInTheDocument();
  });

  it("renders no actions menu without experiment create permission", () => {
    renderCell(false);

    expect(screen.queryByRole("button", { name: "Actions menu" })).toBeNull();
  });
});
