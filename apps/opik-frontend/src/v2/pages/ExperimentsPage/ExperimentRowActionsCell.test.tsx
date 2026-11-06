import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import {
  ColumnDef,
  flexRender,
  getCoreRowModel,
  useReactTable,
} from "@tanstack/react-table";
import PermissionsTestProvider from "@/testing/PermissionsTestProvider";
import { GroupedExperiment } from "@/hooks/useGroupedExperimentsList";
import { EXPERIMENT_TYPE, Experiment } from "@/types/datasets";
import ExperimentRowActionsCell from "./ExperimentRowActionsCell";

const mockBatchDelete = vi.fn();
vi.mock("@/api/datasets/useExperimentBatchDeleteMutation", () => ({
  default: () => ({ mutate: mockBatchDelete }),
}));
vi.mock("@/api/datasets/useExperimentUpdate", () => ({
  default: () => ({ mutate: vi.fn() }),
}));
vi.mock("@/shared/UpdateExperimentDialog/UpdateExperimentDialog", () => ({
  UpdateExperimentDialog: ({ open }: { open: boolean }) =>
    open ? <div data-testid="edit-dialog" /> : null,
}));

const experiment: Experiment = {
  id: "exp-1",
  dataset_id: "dataset-1",
  dataset_name: "Dataset",
  type: EXPERIMENT_TYPE.REGULAR,
  status: "completed",
  name: "Experiment",
  metadata: {},
  trace_count: 0,
  created_at: "2026-09-01T00:00:00Z",
  last_updated_at: "2026-09-01T00:00:00Z",
};

const columns: ColumnDef<GroupedExperiment>[] = [
  { id: "actions", cell: ExperimentRowActionsCell },
];

// Builds a real table so the cell receives a genuine CellContext.
const CellHost = () => {
  const table = useReactTable({
    data: [experiment as GroupedExperiment],
    columns,
    getCoreRowModel: getCoreRowModel(),
  });
  const cell = table.getRowModel().rows[0].getVisibleCells()[0];

  return <>{flexRender(cell.column.columnDef.cell, cell.getContext())}</>;
};

const renderCell = (canCreateExperiments: boolean) =>
  render(
    <PermissionsTestProvider permissions={{ canCreateExperiments }}>
      <CellHost />
    </PermissionsTestProvider>,
  );

const openMenu = () =>
  fireEvent.keyDown(screen.getByRole("button", { name: "Actions menu" }), {
    key: "Enter",
  });

describe("ExperimentRowActionsCell", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("offers Edit and Delete when the user can create experiments", () => {
    renderCell(true);
    openMenu();

    expect(screen.getByRole("menuitem", { name: "Edit" })).toBeInTheDocument();
    expect(
      screen.getByRole("menuitem", { name: "Delete" }),
    ).toBeInTheDocument();
  });

  it("opens the edit dialog from Edit", () => {
    renderCell(true);
    openMenu();

    fireEvent.click(screen.getByRole("menuitem", { name: "Edit" }));

    expect(screen.getByTestId("edit-dialog")).toBeInTheDocument();
  });

  it("deletes the experiment after confirming Delete", () => {
    renderCell(true);
    openMenu();

    fireEvent.click(screen.getByRole("menuitem", { name: "Delete" }));
    fireEvent.click(screen.getByRole("button", { name: "Delete experiment" }));

    expect(mockBatchDelete).toHaveBeenCalledWith({ ids: ["exp-1"] });
  });

  it("renders no actions menu without experiment create permission", () => {
    renderCell(false);

    expect(screen.queryByRole("button", { name: "Actions menu" })).toBeNull();
  });
});
