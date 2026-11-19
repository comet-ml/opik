import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import {
  ColumnDef,
  flexRender,
  getCoreRowModel,
  useReactTable,
} from "@tanstack/react-table";
import PermissionsTestProvider from "@/testing/PermissionsTestProvider";
import { Permissions } from "@/types/permissions";
import { Dataset } from "@/types/datasets";
import { createDatasetRowActionsCell } from "./DatasetRowActionsCell";

const mockIsFeatureEnabled = vi.fn();
vi.mock("@/contexts/feature-toggles-provider", () => ({
  useIsFeatureEnabled: () => mockIsFeatureEnabled(),
}));
vi.mock("@/api/datasets/useDatasetDeleteMutation", () => ({
  default: () => ({ mutate: vi.fn() }),
}));
vi.mock("@/api/datasets/useStartDatasetExportMutation", () => ({
  default: () => ({ mutate: vi.fn(), isPending: false }),
}));
vi.mock("@/store/DatasetExportStore", () => ({
  useAddExportJob: () => vi.fn(),
  useSetPanelExpanded: () => vi.fn(),
}));

const dataset: Dataset = {
  id: "d1",
  name: "Suite",
  dataset_items_count: 0,
  experiment_count: 0,
  most_recent_experiment_at: "",
  last_created_experiment_at: "",
  most_recent_optimization_at: "",
  last_created_optimization_at: "",
  optimization_count: 0,
  created_at: "2026-09-01T00:00:00Z",
  last_updated_at: "2026-09-01T00:00:00Z",
};

const RowActionsCell = createDatasetRowActionsCell({
  entityName: "test suite",
  EditDialog: () => null,
});

const columns: ColumnDef<Dataset>[] = [{ id: "actions", cell: RowActionsCell }];

// Builds a real table so the cell receives a genuine CellContext.
const CellHost = () => {
  const table = useReactTable({
    data: [dataset],
    columns,
    getCoreRowModel: getCoreRowModel(),
  });
  const cell = table.getRowModel().rows[0].getVisibleCells()[0];

  return <>{flexRender(cell.column.columnDef.cell, cell.getContext())}</>;
};

const openMenu = (permissions: Partial<Permissions> = {}) => {
  render(
    <PermissionsTestProvider permissions={permissions}>
      <CellHost />
    </PermissionsTestProvider>,
  );
  fireEvent.keyDown(screen.getByRole("button", { name: "Actions menu" }), {
    key: "Enter",
  });
};

describe("DatasetRowActionsCell Download action", () => {
  beforeEach(() => {
    mockIsFeatureEnabled.mockReturnValue(true);
  });

  it("is offered when the export feature is on and the user can view datasets", () => {
    openMenu();

    expect(
      screen.getByRole("menuitem", { name: "Download" }),
    ).toBeInTheDocument();
  });

  it("is hidden without dataset view permission", () => {
    openMenu({ canViewDatasets: false });

    expect(screen.queryByRole("menuitem", { name: "Download" })).toBeNull();
  });

  it("is hidden when the export feature is off", () => {
    mockIsFeatureEnabled.mockReturnValue(false);
    openMenu();

    expect(screen.queryByRole("menuitem", { name: "Download" })).toBeNull();
  });
});
