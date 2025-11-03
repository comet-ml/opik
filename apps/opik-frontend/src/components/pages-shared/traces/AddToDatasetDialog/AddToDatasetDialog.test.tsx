import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, waitFor, fireEvent } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import AddToDatasetDialog from "./AddToDatasetDialog";
import { Trace, Span, SPAN_TYPE } from "@/types/traces";
import { ReactNode } from "react";

// Mock the API hooks
vi.mock("@/api/datasets/useDatasetsList", () => ({
  default: vi.fn(() => ({
    data: {
      content: [
        {
          id: "dataset-1",
          name: "Test Dataset 1",
          description: "First test dataset",
        },
        {
          id: "dataset-2",
          name: "Test Dataset 2",
          description: "Second test dataset",
        },
      ],
      total: 2,
    },
    isPending: false,
  })),
}));

vi.mock("@/api/datasets/useDatasetItemBatchMutation", () => ({
  default: vi.fn(() => ({
    mutate: vi.fn(),
  })),
}));

vi.mock("@/api/datasets/useAddTracesToDatasetMutation", () => ({
  default: vi.fn(() => ({
    mutate: vi.fn(),
  })),
}));

// Mock the store
vi.mock("@/store/AppStore", () => ({
  default: vi.fn((selector) =>
    selector({
      activeWorkspaceName: "test-workspace",
    }),
  ),
}));

// Mock the toast hook
vi.mock("@/components/ui/use-toast", () => ({
  useToast: () => ({
    toast: vi.fn(),
  }),
}));

// Mock the navigate hook
vi.mock("@/hooks/useNavigateToExperiment", () => ({
  useNavigateToExperiment: () => ({
    navigate: vi.fn(),
  }),
}));

// Mock the AddEditDatasetDialog component
vi.mock("@/components/pages/DatasetsPage/AddEditDatasetDialog", () => ({
  default: () => <div data-testid="add-edit-dataset-dialog" />,
}));

describe("AddToDatasetDialog", () => {
  let queryClient: QueryClient;

  beforeEach(() => {
    queryClient = new QueryClient({
      defaultOptions: {
        queries: { retry: false },
        mutations: { retry: false },
      },
    });
    vi.clearAllMocks();
  });

  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  );

  const mockTrace: Trace = {
    id: "trace-1",
    name: "Test Trace",
    input: { prompt: "test input" },
    output: { response: "test output" },
    start_time: "2024-01-01T00:00:00Z",
    end_time: "2024-01-01T00:00:01Z",
    duration: 1000,
    created_at: "2024-01-01T00:00:00Z",
    last_updated_at: "2024-01-01T00:00:01Z",
    tags: ["tag1", "tag2"],
    metadata: {},
    feedback_scores: [],
    comments: [],
    project_id: "project-1",
  };

  const mockSpan: Span = {
    id: "span-1",
    name: "Test Span",
    type: SPAN_TYPE.llm,
    input: { prompt: "span input" },
    output: { response: "span output" },
    start_time: "2024-01-01T00:00:00Z",
    end_time: "2024-01-01T00:00:01Z",
    duration: 1000,
    created_at: "2024-01-01T00:00:00Z",
    last_updated_at: "2024-01-01T00:00:01Z",
    metadata: {},
    feedback_scores: [],
    comments: [],
    tags: [],
    trace_id: "trace-1",
    parent_span_id: "",
    project_id: "project-1",
  };

  const defaultProps = {
    getDataForExport: vi.fn(async () => [mockTrace]),
    selectedRows: [mockTrace],
    open: true,
    setOpen: vi.fn(),
  };

  it("should render the dialog when open", () => {
    render(<AddToDatasetDialog {...defaultProps} />, { wrapper });

    expect(screen.getByText("Add to dataset")).toBeInTheDocument();
    expect(screen.getByText("Select a dataset")).toBeInTheDocument();
  });

  it("should display enrichment checkboxes when only traces are selected", () => {
    render(<AddToDatasetDialog {...defaultProps} />, { wrapper });

    expect(screen.getByText("Include trace metadata")).toBeInTheDocument();
    expect(screen.getByLabelText("Nested spans")).toBeInTheDocument();
    expect(screen.getByLabelText("Tags")).toBeInTheDocument();
    expect(screen.getByLabelText("Feedback scores")).toBeInTheDocument();
    expect(screen.getByLabelText("Comments")).toBeInTheDocument();
    expect(screen.getByLabelText("Usage metrics")).toBeInTheDocument();
    expect(screen.getByLabelText("Metadata")).toBeInTheDocument();
  });

  it("should have all enrichment checkboxes checked by default", () => {
    render(<AddToDatasetDialog {...defaultProps} />, { wrapper });

    expect(screen.getByLabelText("Nested spans")).toBeChecked();
    expect(screen.getByLabelText("Tags")).toBeChecked();
    expect(screen.getByLabelText("Feedback scores")).toBeChecked();
    expect(screen.getByLabelText("Comments")).toBeChecked();
    expect(screen.getByLabelText("Usage metrics")).toBeChecked();
    expect(screen.getByLabelText("Metadata")).toBeChecked();
  });

  it("should allow unchecking enrichment options", async () => {
    render(<AddToDatasetDialog {...defaultProps} />, { wrapper });

    const spansCheckbox = screen.getByLabelText("Nested spans");
    const tagsCheckbox = screen.getByLabelText("Tags");

    fireEvent.click(spansCheckbox);
    fireEvent.click(tagsCheckbox);

    await waitFor(() => {
      expect(spansCheckbox).not.toBeChecked();
      expect(tagsCheckbox).not.toBeChecked();
    });
    expect(screen.getByLabelText("Feedback scores")).toBeChecked();
  });

  it("should not display enrichment checkboxes when spans are selected", () => {
    const propsWithSpan = {
      ...defaultProps,
      selectedRows: [mockSpan],
      getDataForExport: vi.fn(async () => [mockSpan]),
    };

    render(<AddToDatasetDialog {...propsWithSpan} />, { wrapper });

    expect(
      screen.queryByText("Include trace metadata"),
    ).not.toBeInTheDocument();
    expect(screen.queryByLabelText("Nested spans")).not.toBeInTheDocument();
  });

  it("should not display enrichment checkboxes when mixed traces and spans are selected", () => {
    const propsWithMixed = {
      ...defaultProps,
      selectedRows: [mockTrace, mockSpan],
      getDataForExport: vi.fn(async () => [mockTrace, mockSpan]),
    };

    render(<AddToDatasetDialog {...propsWithMixed} />, { wrapper });

    expect(
      screen.queryByText("Include trace metadata"),
    ).not.toBeInTheDocument();
  });

  it("should display available datasets", () => {
    render(<AddToDatasetDialog {...defaultProps} />, { wrapper });

    expect(screen.getByText("Test Dataset 1")).toBeInTheDocument();
    expect(screen.getByText("First test dataset")).toBeInTheDocument();
    expect(screen.getByText("Test Dataset 2")).toBeInTheDocument();
    expect(screen.getByText("Second test dataset")).toBeInTheDocument();
  });

  it("should display search input", () => {
    render(<AddToDatasetDialog {...defaultProps} />, { wrapper });

    const searchInput = screen.getByPlaceholderText("Search");
    expect(searchInput).toBeInTheDocument();
  });

  it("should display create new dataset button", () => {
    render(<AddToDatasetDialog {...defaultProps} />, { wrapper });

    expect(screen.getByText("Create new dataset")).toBeInTheDocument();
  });

  it("should show alert when no valid rows are present", () => {
    const propsWithInvalidRows = {
      ...defaultProps,
      selectedRows: [{ ...mockTrace, input: undefined as unknown as object }],
    };

    render(<AddToDatasetDialog {...propsWithInvalidRows} />, { wrapper });

    expect(
      screen.getByText(
        "There are no rows that can be added as dataset items. The input field is missing.",
      ),
    ).toBeInTheDocument();
  });

  it("should show alert when only some rows are valid", () => {
    const propsWithPartialValid = {
      ...defaultProps,
      selectedRows: [
        mockTrace,
        { ...mockTrace, id: "trace-2", input: undefined as unknown as object },
      ],
    };

    render(<AddToDatasetDialog {...propsWithPartialValid} />, { wrapper });

    expect(
      screen.getByText(
        "Only rows with input fields will be added as dataset items.",
      ),
    ).toBeInTheDocument();
  });

  it("should disable create new dataset button when no valid rows", () => {
    const propsWithInvalidRows = {
      ...defaultProps,
      selectedRows: [{ ...mockTrace, input: undefined as unknown as object }],
    };

    render(<AddToDatasetDialog {...propsWithInvalidRows} />, { wrapper });

    const createButton = screen.getByText("Create new dataset");
    expect(createButton).toBeDisabled();
  });

  it("should call addTracesToDataset mutation when clicking on dataset with only traces", async () => {
    const mockAddTracesToDataset = vi.fn();
    const useAddTracesToDatasetMutation = await import(
      "@/api/datasets/useAddTracesToDatasetMutation"
    );
    vi.mocked(useAddTracesToDatasetMutation.default).mockReturnValue({
      mutate: mockAddTracesToDataset,
      mutateAsync: vi.fn(),
      isPending: false,
      isError: false,
      isSuccess: false,
      isIdle: true,
      data: undefined,
      error: null,
      status: "idle",
      reset: vi.fn(),
      variables: undefined,
      context: undefined,
      failureCount: 0,
      failureReason: null,
      isPaused: false,
      submittedAt: 0,
    });

    render(<AddToDatasetDialog {...defaultProps} />, { wrapper });

    const dataset = screen.getByText("Test Dataset 1");
    fireEvent.click(dataset);

    await waitFor(() => {
      expect(mockAddTracesToDataset).toHaveBeenCalledWith(
        expect.objectContaining({
          datasetId: "dataset-1",
          traceIds: ["trace-1"],
          enrichmentOptions: {
            include_spans: true,
            include_tags: true,
            include_feedback_scores: true,
            include_comments: true,
            include_usage: true,
            include_metadata: true,
          },
          workspaceName: "test-workspace",
        }),
        expect.any(Object),
      );
    });
  });

  it("should call batch mutation when clicking on dataset with spans", async () => {
    const mockBatchMutate = vi.fn();
    const useDatasetItemBatchMutation = await import(
      "@/api/datasets/useDatasetItemBatchMutation"
    );
    vi.mocked(useDatasetItemBatchMutation.default).mockReturnValue({
      mutate: mockBatchMutate,
      mutateAsync: vi.fn(),
      isPending: false,
      isError: false,
      isSuccess: false,
      isIdle: true,
      data: undefined,
      error: null,
      status: "idle",
      reset: vi.fn(),
      variables: undefined,
      context: undefined,
      failureCount: 0,
      failureReason: null,
      isPaused: false,
      submittedAt: 0,
    });

    const propsWithSpan = {
      ...defaultProps,
      selectedRows: [mockSpan],
      getDataForExport: vi.fn(async () => [mockSpan]),
    };

    render(<AddToDatasetDialog {...propsWithSpan} />, { wrapper });

    const dataset = screen.getByText("Test Dataset 1");
    fireEvent.click(dataset);

    await waitFor(() => {
      expect(mockBatchMutate).toHaveBeenCalled();
    });
  });

  it("should respect unchecked enrichment options when adding traces", async () => {
    const mockAddTracesToDataset = vi.fn();
    const useAddTracesToDatasetMutation = await import(
      "@/api/datasets/useAddTracesToDatasetMutation"
    );
    vi.mocked(useAddTracesToDatasetMutation.default).mockReturnValue({
      mutate: mockAddTracesToDataset,
      mutateAsync: vi.fn(),
      isPending: false,
      isError: false,
      isSuccess: false,
      isIdle: true,
      data: undefined,
      error: null,
      status: "idle",
      reset: vi.fn(),
      variables: undefined,
      context: undefined,
      failureCount: 0,
      failureReason: null,
      isPaused: false,
      submittedAt: 0,
    });

    render(<AddToDatasetDialog {...defaultProps} />, { wrapper });

    // Uncheck some options
    fireEvent.click(screen.getByLabelText("Nested spans"));
    fireEvent.click(screen.getByLabelText("Tags"));
    fireEvent.click(screen.getByLabelText("Usage metrics"));

    const dataset = screen.getByText("Test Dataset 1");
    fireEvent.click(dataset);

    await waitFor(() => {
      expect(mockAddTracesToDataset).toHaveBeenCalledWith(
        expect.objectContaining({
          enrichmentOptions: {
            include_spans: false,
            include_tags: false,
            include_feedback_scores: true,
            include_comments: true,
            include_usage: false,
            include_metadata: true,
          },
        }),
        expect.any(Object),
      );
    });
  });
});
