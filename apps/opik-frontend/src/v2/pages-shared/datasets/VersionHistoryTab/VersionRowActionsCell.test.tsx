import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { CellContext } from "@tanstack/react-table";
import { TooltipProvider } from "@/ui/tooltip";
import VersionRowActionsCell from "./VersionRowActionsCell";
import useDatasetItemsList, {
  UseDatasetItemsListResponse,
} from "@/api/datasets/useDatasetItemsList";
import useRestoreDatasetVersionMutation from "@/api/datasets/useRestoreDatasetVersionMutation";
import { DATASET_ITEM_SOURCE, DatasetVersion } from "@/types/datasets";
import { DYNAMIC_COLUMN_TYPE } from "@/types/shared";

vi.mock("@/api/datasets/useDatasetItemsList", () => ({
  default: vi.fn(),
}));

vi.mock("@/api/datasets/useRestoreDatasetVersionMutation", () => ({
  default: vi.fn(),
}));

vi.mock("@/api/datasets/useEditDatasetVersionMutation", () => ({
  default: vi.fn(() => ({ mutate: vi.fn(), isPending: false })),
}));

vi.mock("@/store/TestSuiteDraftStore", () => ({
  useHasDraft: vi.fn(() => false),
  useClearDraft: vi.fn(),
}));

const mockUseDatasetItemsList = vi.mocked(useDatasetItemsList);
const mockUseRestoreDatasetVersionMutation = vi.mocked(
  useRestoreDatasetVersionMutation,
);

const mockVersion: DatasetVersion = {
  id: "version-1",
  dataset_id: "dataset-1",
  version_hash: "abc123",
  version_name: "v2",
  items_total: 1,
  items_added: 0,
  items_modified: 0,
  items_deleted: 0,
  created_at: "2024-01-01T00:00:00Z",
  created_by: "user",
  last_updated_at: "2024-01-01T00:00:00Z",
  last_updated_by: "user",
};

const mockItemsResponse: UseDatasetItemsListResponse = {
  content: [
    {
      id: "item-1",
      data: { input: "hello" },
      source: DATASET_ITEM_SOURCE.manual,
      created_at: "2024-01-01T00:00:00Z",
      last_updated_at: "2024-01-01T00:00:00Z",
    },
  ],
  columns: [{ name: "input", types: [DYNAMIC_COLUMN_TYPE.string] }],
  total: 1,
};

const mockCellContext = {
  row: { original: mockVersion },
  column: {
    columnDef: {
      meta: {
        custom: { datasetId: "dataset-1" },
      },
    },
  },
  table: { options: { meta: {} } },
  cell: {},
} as unknown as CellContext<DatasetVersion, unknown>;

describe("VersionRowActionsCell", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockUseDatasetItemsList.mockReturnValue({
      data: mockItemsResponse,
      isLoading: false,
      isPending: false,
      isPlaceholderData: false,
      isFetching: false,
    } as never);
    mockUseRestoreDatasetVersionMutation.mockReturnValue({
      mutate: vi.fn(),
      isPending: false,
    } as never);
  });

  it("should open the version data dialog from the actions menu", async () => {
    render(<VersionRowActionsCell {...mockCellContext} />, {
      wrapper: ({ children }) => <TooltipProvider>{children}</TooltipProvider>,
    });

    fireEvent.pointerDown(
      screen.getByRole("button", { name: /actions menu/i }),
      { button: 0, ctrlKey: false, pointerType: "mouse" },
    );
    fireEvent.click(await screen.findByText("View data"));

    await waitFor(() => {
      expect(screen.getByText("Version v2 data")).toBeInTheDocument();
    });
    expect(mockUseDatasetItemsList).toHaveBeenCalledWith(
      expect.objectContaining({
        datasetId: "dataset-1",
        versionId: "abc123",
      }),
      expect.objectContaining({ enabled: true }),
    );
  });
});
