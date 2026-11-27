import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import {
  ColumnDef,
  flexRender,
  getCoreRowModel,
  useReactTable,
} from "@tanstack/react-table";
import PermissionsTestProvider from "@/testing/PermissionsTestProvider";
import { TooltipProvider } from "@/ui/tooltip";
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
const mockStartExport = vi.fn();
const mockIsExportStarting = vi.fn();
vi.mock("@/api/datasets/useStartDatasetExportMutation", () => ({
  default: () => ({
    mutate: mockStartExport,
    isPending: mockIsExportStarting(),
  }),
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
    <TooltipProvider>
      <PermissionsTestProvider permissions={permissions}>
        <CellHost />
      </PermissionsTestProvider>
    </TooltipProvider>,
  );
  fireEvent.click(screen.getByRole("button", { name: "More actions" }));
};

describe("DatasetRowActionsCell Download action", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockIsFeatureEnabled.mockReturnValue(true);
    mockIsExportStarting.mockReturnValue(false);
  });

  it("is offered when the export feature is on and the user can view datasets", () => {
    openMenu();

    expect(screen.getByRole("button", { name: "Download" })).toBeEnabled();
  });

  it("starts an export when clicked", () => {
    openMenu();

    fireEvent.click(screen.getByRole("button", { name: "Download" }));

    expect(mockStartExport).toHaveBeenCalledWith(
      { datasetId: "d1" },
      expect.any(Object),
    );
  });

  it("is disabled while an export is starting", () => {
    mockIsExportStarting.mockReturnValue(true);
    openMenu();

    const download = screen.getByRole("button", { name: "Download" });
    expect(download).toHaveAttribute("aria-disabled", "true");

    fireEvent.click(download);

    expect(mockStartExport).not.toHaveBeenCalled();
  });

  it("is hidden without dataset view permission", () => {
    openMenu({ canViewDatasets: false });

    expect(screen.queryByRole("button", { name: "Download" })).toBeNull();
  });

  it("is hidden when the export feature is off", () => {
    mockIsFeatureEnabled.mockReturnValue(false);
    openMenu();

    expect(screen.queryByRole("button", { name: "Download" })).toBeNull();
  });
});
