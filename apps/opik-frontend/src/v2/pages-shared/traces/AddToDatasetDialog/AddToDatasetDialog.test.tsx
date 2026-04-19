import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, waitFor, fireEvent } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import AddToDatasetDialog from "./AddToDatasetDialog";
import { Trace, Span, SPAN_TYPE } from "@/types/traces";
import { ReactNode } from "react";
import { PermissionsProvider } from "@/contexts/PermissionsContext";
import { DEFAULT_PERMISSIONS } from "@/types/permissions";
import { DATASET_TYPE } from "@/types/datasets";

const mockAddTracesToDataset = vi.fn();
const mockAddSpansToDataset = vi.fn();

const ALL_DATASETS = [
  {
    id: "dataset-1",
    name: "Test Dataset 1",
    description: "First test dataset",
    type: DATASET_TYPE.DATASET,
  },
  {
    id: "suite-1",
    name: "Test Suite 1",
    description: "First test suite",
    type: DATASET_TYPE.TEST_SUITE,
  },
];

vi.mock("@/api/datasets/useProjectDatasetsList", () => ({
  default: vi.fn(
    (params: { filters?: Array<{ field: string; value: string }> }) => {
      const typeFilter = params.filters?.find((f) => f.field === "type");
      const content = typeFilter
        ? ALL_DATASETS.filter((d) => d.type === typeFilter.value)
        : ALL_DATASETS;
      return {
        data: { content, total: content.length },
        isPending: false,
      };
    },
  ),
}));

vi.mock("@/api/datasets/useDatasetVersionsList", () => ({
  default: vi.fn(() => ({
    data: { content: [], total: 0 },
  })),
}));

vi.mock("@/api/datasets/useAddTracesToDatasetMutation", () => ({
  default: () => ({
    mutate: mockAddTracesToDataset,
  }),
}));

vi.mock("@/api/datasets/useAddSpansToDatasetMutation", () => ({
  default: () => ({
    mutate: mockAddSpansToDataset,
  }),
}));

vi.mock("@/store/AppStore", () => ({
  default: vi.fn((selector) =>
    selector({
      activeWorkspaceName: "test-workspace",
      activeProjectId: "test-project-id",
    }),
  ),
  useActiveProjectId: () => "test-project-id",
}));

vi.mock("@/ui/use-toast", () => ({
  useToast: () => ({
    toast: vi.fn(),
  }),
}));

vi.mock(
  "@/v2/pages-shared/datasets/AddEditTestSuiteDialog/AddEditTestSuiteDialog",
  () => ({
    default: () => <div data-testid="add-edit-test-suite-dialog" />,
  }),
);

vi.mock(
  "@/v2/pages-shared/datasets/AddEditDatasetDialog/AddEditDatasetDialog",
  () => ({
    default: () => <div data-testid="add-edit-dataset-dialog" />,
  }),
);

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
    <QueryClientProvider client={queryClient}>
      <PermissionsProvider value={DEFAULT_PERMISSIONS}>
        {children}
      </PermissionsProvider>
    </QueryClientProvider>
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

  const baseProps = {
    selectedRows: [mockTrace],
    open: true,
    setOpen: vi.fn(),
  };

  const datasetModeProps = {
    ...baseProps,
    datasetType: DATASET_TYPE.DATASET,
  };

  const testSuiteModeProps = {
    ...baseProps,
    datasetType: DATASET_TYPE.TEST_SUITE,
  };

  it("should render the test suite dialog when open", () => {
    render(<AddToDatasetDialog {...testSuiteModeProps} />, { wrapper });

    expect(screen.getByText("Select a test suite")).toBeInTheDocument();
    expect(screen.getByText("Test Suite 1")).toBeInTheDocument();
  });

  it("should display enrichment checkboxes when selecting a dataset with traces", () => {
    render(<AddToDatasetDialog {...datasetModeProps} />, { wrapper });

    fireEvent.click(screen.getByText("Test Dataset 1"));

    expect(screen.getByLabelText("Nested spans")).toBeInTheDocument();
    expect(screen.getByLabelText("Tags")).toBeInTheDocument();
    expect(screen.getByLabelText("Feedback scores")).toBeInTheDocument();
    expect(screen.getByLabelText("Comments")).toBeInTheDocument();
    expect(screen.getByLabelText("Usage metrics")).toBeInTheDocument();
    expect(screen.getByLabelText("Metadata")).toBeInTheDocument();
  });

  it("should have all enrichment checkboxes checked by default", () => {
    render(<AddToDatasetDialog {...datasetModeProps} />, { wrapper });

    fireEvent.click(screen.getByText("Test Dataset 1"));

    expect(screen.getByLabelText("Nested spans")).toBeChecked();
    expect(screen.getByLabelText("Tags")).toBeChecked();
    expect(screen.getByLabelText("Feedback scores")).toBeChecked();
    expect(screen.getByLabelText("Comments")).toBeChecked();
    expect(screen.getByLabelText("Usage metrics")).toBeChecked();
    expect(screen.getByLabelText("Metadata")).toBeChecked();
  });

  it("should allow unchecking enrichment options", async () => {
    render(<AddToDatasetDialog {...datasetModeProps} />, { wrapper });

    fireEvent.click(screen.getByText("Test Dataset 1"));

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

  it("should display span enrichment checkboxes when selecting a dataset with spans", () => {
    const propsWithSpan = {
      ...datasetModeProps,
      selectedRows: [mockSpan],
    };

    render(<AddToDatasetDialog {...propsWithSpan} />, { wrapper });

    fireEvent.click(screen.getByText("Test Dataset 1"));

    expect(screen.queryByLabelText("Nested spans")).not.toBeInTheDocument();
    expect(screen.getByLabelText("Tags")).toBeInTheDocument();
    expect(screen.getByLabelText("Feedback scores")).toBeInTheDocument();
    expect(screen.getByLabelText("Comments")).toBeInTheDocument();
    expect(screen.getByLabelText("Usage metrics")).toBeInTheDocument();
    expect(screen.getByLabelText("Metadata")).toBeInTheDocument();
  });

  it("should have all span enrichment checkboxes checked by default", () => {
    const propsWithSpan = {
      ...datasetModeProps,
      selectedRows: [mockSpan],
    };

    render(<AddToDatasetDialog {...propsWithSpan} />, { wrapper });

    fireEvent.click(screen.getByText("Test Dataset 1"));

    expect(screen.getByLabelText("Tags")).toBeChecked();
    expect(screen.getByLabelText("Feedback scores")).toBeChecked();
    expect(screen.getByLabelText("Comments")).toBeChecked();
    expect(screen.getByLabelText("Usage metrics")).toBeChecked();
    expect(screen.getByLabelText("Metadata")).toBeChecked();
  });

  it("should allow unchecking span enrichment options", async () => {
    const propsWithSpan = {
      ...datasetModeProps,
      selectedRows: [mockSpan],
    };

    render(<AddToDatasetDialog {...propsWithSpan} />, { wrapper });

    fireEvent.click(screen.getByText("Test Dataset 1"));

    const tagsCheckbox = screen.getByLabelText("Tags");
    const usageCheckbox = screen.getByLabelText("Usage metrics");

    fireEvent.click(tagsCheckbox);
    fireEvent.click(usageCheckbox);

    await waitFor(() => {
      expect(tagsCheckbox).not.toBeChecked();
      expect(usageCheckbox).not.toBeChecked();
    });
    expect(screen.getByLabelText("Feedback scores")).toBeChecked();
  });

  it("should list only datasets matching dataset type when adding to a dataset", () => {
    render(<AddToDatasetDialog {...datasetModeProps} />, { wrapper });

    expect(screen.getByText("Test Dataset 1")).toBeInTheDocument();
    expect(screen.getByText("First test dataset")).toBeInTheDocument();
    expect(screen.queryByText("Test Suite 1")).not.toBeInTheDocument();
  });

  it("should list only test suites when adding to a test suite", () => {
    render(<AddToDatasetDialog {...testSuiteModeProps} />, { wrapper });

    expect(screen.getByText("Test Suite 1")).toBeInTheDocument();
    expect(screen.getByText("First test suite")).toBeInTheDocument();
    expect(screen.queryByText("Test Dataset 1")).not.toBeInTheDocument();
  });

  it("should display search input", () => {
    render(<AddToDatasetDialog {...datasetModeProps} />, { wrapper });

    const searchInput = screen.getByPlaceholderText("Search");
    expect(searchInput).toBeInTheDocument();
  });

  it("should display create new test suite button", () => {
    render(<AddToDatasetDialog {...testSuiteModeProps} />, { wrapper });

    expect(screen.getByText("Create new test suite")).toBeInTheDocument();
  });

  it("should show alert when no valid rows are present", () => {
    const propsWithInvalidRows = {
      ...testSuiteModeProps,
      selectedRows: [{ ...mockTrace, input: undefined as unknown as object }],
    };

    render(<AddToDatasetDialog {...propsWithInvalidRows} />, { wrapper });

    expect(
      screen.getByText(
        "There are no rows that can be added as test suite items. The input field is missing.",
      ),
    ).toBeInTheDocument();
  });

  it("should show alert when only some rows are valid", () => {
    const propsWithPartialValid = {
      ...testSuiteModeProps,
      selectedRows: [
        mockTrace,
        { ...mockTrace, id: "trace-2", input: undefined as unknown as object },
      ],
    };

    render(<AddToDatasetDialog {...propsWithPartialValid} />, { wrapper });

    expect(
      screen.getByText(
        "Only rows with input fields will be added as test suite items.",
      ),
    ).toBeInTheDocument();
  });

  it("should disable create new test suite button when no valid rows", () => {
    const propsWithInvalidRows = {
      ...testSuiteModeProps,
      selectedRows: [{ ...mockTrace, input: undefined as unknown as object }],
    };

    render(<AddToDatasetDialog {...propsWithInvalidRows} />, { wrapper });

    const createButton = screen.getByText("Create new test suite");
    expect(createButton).toBeDisabled();
  });

  it("should call addTracesToDataset mutation when clicking on dataset with only traces", async () => {
    render(<AddToDatasetDialog {...datasetModeProps} />, { wrapper });

    fireEvent.click(screen.getByText("Test Dataset 1"));

    fireEvent.click(screen.getByRole("button", { name: "Add to dataset" }));

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

  it("should call addSpansToDataset mutation when clicking on dataset with only spans", async () => {
    const propsWithSpan = {
      ...datasetModeProps,
      selectedRows: [mockSpan],
    };

    render(<AddToDatasetDialog {...propsWithSpan} />, { wrapper });

    fireEvent.click(screen.getByText("Test Dataset 1"));

    fireEvent.click(screen.getByRole("button", { name: "Add to dataset" }));

    await waitFor(() => {
      expect(mockAddSpansToDataset).toHaveBeenCalledWith(
        expect.objectContaining({
          datasetId: "dataset-1",
          spanIds: ["span-1"],
          enrichmentOptions: {
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

  it("should respect unchecked enrichment options when adding traces", async () => {
    render(<AddToDatasetDialog {...datasetModeProps} />, { wrapper });

    fireEvent.click(screen.getByText("Test Dataset 1"));

    fireEvent.click(screen.getByLabelText("Nested spans"));
    fireEvent.click(screen.getByLabelText("Tags"));
    fireEvent.click(screen.getByLabelText("Usage metrics"));

    fireEvent.click(screen.getByRole("button", { name: "Add to dataset" }));

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

  it("should respect unchecked enrichment options when adding spans", async () => {
    const propsWithSpan = {
      ...datasetModeProps,
      selectedRows: [mockSpan],
    };

    render(<AddToDatasetDialog {...propsWithSpan} />, { wrapper });

    fireEvent.click(screen.getByText("Test Dataset 1"));

    fireEvent.click(screen.getByLabelText("Tags"));
    fireEvent.click(screen.getByLabelText("Comments"));
    fireEvent.click(screen.getByLabelText("Metadata"));

    fireEvent.click(screen.getByRole("button", { name: "Add to dataset" }));

    await waitFor(() => {
      expect(mockAddSpansToDataset).toHaveBeenCalledWith(
        expect.objectContaining({
          enrichmentOptions: {
            include_tags: false,
            include_feedback_scores: true,
            include_comments: false,
            include_usage: true,
            include_metadata: false,
          },
        }),
        expect.any(Object),
      );
    });
  });
});
