import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, RenderOptions } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { TooltipProvider } from "@/ui/tooltip";
import ViewVersionDataDialog from "./ViewVersionDataDialog";
import useDatasetItemsList, {
  UseDatasetItemsListResponse,
} from "@/api/datasets/useDatasetItemsList";
import { DATASET_ITEM_SOURCE, DatasetVersion } from "@/types/datasets";
import { DYNAMIC_COLUMN_TYPE } from "@/types/shared";

vi.mock("@/api/datasets/useDatasetItemsList", () => ({
  default: vi.fn(),
}));

const mockUseDatasetItemsList = vi.mocked(useDatasetItemsList);

const mockVersion: DatasetVersion = {
  id: "version-1",
  dataset_id: "dataset-1",
  version_hash: "abc123",
  version_name: "v2",
  items_total: 2,
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
      data: { input: "hello", output: "world" },
      source: DATASET_ITEM_SOURCE.manual,
      created_at: "2024-01-01T00:00:00Z",
      last_updated_at: "2024-01-01T00:00:00Z",
    },
    {
      id: "item-2",
      data: { input: "foo", output: "bar" },
      source: DATASET_ITEM_SOURCE.manual,
      created_at: "2024-01-02T00:00:00Z",
      last_updated_at: "2024-01-02T00:00:00Z",
    },
  ],
  columns: [
    { name: "input", types: [DYNAMIC_COLUMN_TYPE.string] },
    { name: "output", types: [DYNAMIC_COLUMN_TYPE.string] },
  ],
  total: 2,
};

const renderWithProviders = (ui: React.ReactElement, options?: RenderOptions) =>
  render(ui, {
    wrapper: ({ children }) => {
      const queryClient = new QueryClient({
        defaultOptions: { queries: { retry: false } },
      });
      return (
        <QueryClientProvider client={queryClient}>
          <TooltipProvider>{children}</TooltipProvider>
        </QueryClientProvider>
      );
    },
    ...options,
  });

const renderDialog = (open = true) =>
  renderWithProviders(
    <ViewVersionDataDialog
      open={open}
      setOpen={vi.fn()}
      datasetId="dataset-1"
      version={mockVersion}
    />,
  );

describe("ViewVersionDataDialog", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockUseDatasetItemsList.mockReturnValue({
      data: mockItemsResponse,
      isLoading: false,
      isPending: false,
      isPlaceholderData: false,
      isFetching: false,
    } as never);
  });

  it("should render the version name in the dialog title", () => {
    renderDialog();

    expect(screen.getByText("Version v2 data")).toBeInTheDocument();
    expect(screen.getByText(/read-only mode/i)).toBeInTheDocument();
  });

  it("should fetch items with the version hash and render them read-only", () => {
    renderDialog();

    expect(mockUseDatasetItemsList).toHaveBeenCalledWith(
      expect.objectContaining({
        datasetId: "dataset-1",
        page: 1,
        size: 10,
        versionId: "abc123",
      }),
      expect.objectContaining({ enabled: true }),
    );

    expect(screen.getAllByText("item-1").length).toBeGreaterThan(0);
    expect(screen.getAllByText("item-2").length).toBeGreaterThan(0);
    expect(screen.getByText("hello")).toBeInTheDocument();
    expect(screen.getByText("world")).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: /add/i }),
    ).not.toBeInTheDocument();
  });

  it("should show the no-data state when the version has no items", () => {
    mockUseDatasetItemsList.mockReturnValue({
      data: { content: [], columns: [], total: 0 },
      isLoading: false,
      isPending: false,
      isPlaceholderData: false,
      isFetching: false,
    } as never);

    renderDialog();

    expect(screen.getByText("No records in this version")).toBeInTheDocument();
  });

  it("should not fetch items when the dialog is closed", () => {
    renderDialog(false);

    expect(mockUseDatasetItemsList).toHaveBeenCalledWith(
      expect.objectContaining({
        datasetId: "dataset-1",
        versionId: "abc123",
      }),
      expect.objectContaining({ enabled: false }),
    );
    expect(screen.queryByText("Version v2 data")).not.toBeInTheDocument();
  });
});
