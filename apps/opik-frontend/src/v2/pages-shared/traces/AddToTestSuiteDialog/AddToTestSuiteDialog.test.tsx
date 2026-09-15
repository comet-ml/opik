import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, waitFor, fireEvent } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import AddToTestSuiteDialog from "./AddToTestSuiteDialog";
import { Trace, Span, SPAN_TYPE } from "@/types/traces";
import { ReactNode } from "react";
import { PermissionsProvider } from "@/contexts/PermissionsContext";
import { DEFAULT_PERMISSIONS } from "@/types/permissions";
import { DATASET_TYPE } from "@/types/datasets";
import { TooltipProvider } from "@/ui/tooltip";

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
    id: "dataset-2",
    name: "Test Dataset 2",
    description: "Second test dataset",
    type: DATASET_TYPE.DATASET,
  },
  {
    id: "suite-1",
    name: "Test Suite 1",
    description: "First test suite",
    type: DATASET_TYPE.TEST_SUITE,
  },
  {
    id: "suite-2",
    name: "Test Suite 2",
    description: "Second test suite",
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

describe("AddToTestSuiteDialog", () => {
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
      <TooltipProvider>
        <PermissionsProvider value={DEFAULT_PERMISSIONS}>
          {children}
        </PermissionsProvider>
      </TooltipProvider>
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

  const openDropdownAndSelect = (itemName: string) => {
    const trigger = screen.getByRole("button", {
      name: new RegExp(`Select a test suite|${itemName}`),
    });
    fireEvent.click(trigger);
    const items = screen.getAllByText(itemName);
    fireEvent.click(items[items.length - 1]);
  };

  it("should render the test suite dialog when open", () => {
    render(<AddToTestSuiteDialog {...baseProps} />, { wrapper });

    expect(
      screen.getByRole("button", { name: /Select a test suite/i }),
    ).toBeInTheDocument();
  });

  it("should list only test suites when adding to a test suite", () => {
    render(<AddToTestSuiteDialog {...baseProps} />, { wrapper });

    const trigger = screen.getByRole("button", {
      name: /Select a test suite/i,
    });
    fireEvent.click(trigger);

    expect(screen.getByText("Test Suite 1")).toBeInTheDocument();
    expect(screen.getByText("First test suite")).toBeInTheDocument();
    expect(screen.queryByText("Test Dataset 1")).not.toBeInTheDocument();
  });

  it("should display add test suite option in dropdown", () => {
    render(<AddToTestSuiteDialog {...baseProps} />, { wrapper });

    const trigger = screen.getByRole("button", {
      name: /Select a test suite/i,
    });
    fireEvent.click(trigger);

    expect(screen.getByText("Add test suite")).toBeInTheDocument();
  });

  it("should not display enrichment checkboxes when a test suite is selected", () => {
    render(<AddToTestSuiteDialog {...baseProps} />, { wrapper });

    openDropdownAndSelect("Test Suite 1");

    expect(screen.queryByLabelText("Nested spans")).not.toBeInTheDocument();
    expect(screen.queryByLabelText("Feedback scores")).not.toBeInTheDocument();
  });

  it("should show alert when no valid rows are present", () => {
    const propsWithInvalidRows = {
      ...baseProps,
      selectedRows: [{ ...mockTrace, input: undefined as unknown as object }],
    };

    render(<AddToTestSuiteDialog {...propsWithInvalidRows} />, { wrapper });

    expect(
      screen.getByText(
        "There are no rows that can be added as test suite items. The input field is missing.",
      ),
    ).toBeInTheDocument();
  });

  it("should show alert when only some rows are valid", () => {
    const propsWithPartialValid = {
      ...baseProps,
      selectedRows: [
        mockTrace,
        { ...mockTrace, id: "trace-2", input: undefined as unknown as object },
      ],
    };

    render(<AddToTestSuiteDialog {...propsWithPartialValid} />, { wrapper });

    expect(
      screen.getByText(
        "Only rows with input fields will be added as test suite items.",
      ),
    ).toBeInTheDocument();
  });

  it("should disable dropdown when no valid rows", () => {
    const propsWithInvalidRows = {
      ...baseProps,
      selectedRows: [{ ...mockTrace, input: undefined as unknown as object }],
    };

    render(<AddToTestSuiteDialog {...propsWithInvalidRows} />, { wrapper });

    const trigger = screen.getByRole("button", {
      name: /Select a test suite/i,
    });
    expect(trigger).toBeDisabled();
  });

  it("should call addTracesToDataset mutation when adding traces to a test suite", async () => {
    render(<AddToTestSuiteDialog {...baseProps} />, { wrapper });

    openDropdownAndSelect("Test Suite 1");

    fireEvent.click(screen.getByRole("button", { name: "Add to test suite" }));

    await waitFor(() => {
      expect(mockAddTracesToDataset).toHaveBeenCalledWith(
        expect.objectContaining({
          datasetId: "suite-1",
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

  it("should call addSpansToDataset mutation when adding spans to a test suite", async () => {
    const propsWithSpan = {
      ...baseProps,
      selectedRows: [mockSpan],
    };

    render(<AddToTestSuiteDialog {...propsWithSpan} />, { wrapper });

    openDropdownAndSelect("Test Suite 1");

    fireEvent.click(screen.getByRole("button", { name: "Add to test suite" }));

    await waitFor(() => {
      expect(mockAddSpansToDataset).toHaveBeenCalledWith(
        expect.objectContaining({
          datasetId: "suite-1",
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

  it("should send assertions as evaluators when adding traces", async () => {
    render(<AddToTestSuiteDialog {...baseProps} />, { wrapper });

    openDropdownAndSelect("Test Suite 1");

    fireEvent.click(screen.getByRole("button", { name: /Add assertion/i }));
    fireEvent.change(screen.getByRole("textbox"), {
      target: { value: "The answer is polite" },
    });

    fireEvent.click(screen.getByRole("button", { name: "Add to test suite" }));

    await waitFor(() => {
      expect(mockAddTracesToDataset).toHaveBeenCalledWith(
        expect.objectContaining({
          evaluators: [
            expect.objectContaining({
              type: "llm_judge",
            }),
          ],
        }),
        expect.any(Object),
      );
    });
  });
});
