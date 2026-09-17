import { describe, it, expect, vi, beforeEach } from "vitest";
import {
  render,
  screen,
  waitFor,
  fireEvent,
  within,
} from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import AddToDatasetDialog from "./AddToDatasetDialog";
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

const POPULATED_DATASET = {
  columns: [
    { name: "input" },
    { name: "expected_output" },
    { name: "metadata" },
    { name: "bucket" },
    { name: "tone" },
  ],
  total: 412,
};

const EMPTY_DATASET = { columns: [], total: 0 };

const mockDatasetColumns = vi.fn(() => POPULATED_DATASET);

vi.mock("@/api/datasets/useDatasetItemsList", () => ({
  default: () => ({ data: mockDatasetColumns() }),
}));

type SampleResult = { data?: Partial<Trace>; isPending: boolean };

const mockTracesByIds = vi.fn(
  ({ traceIds }: { traceIds: string[] }): SampleResult[] =>
    traceIds.map((id) => ({
      data: { id, input: { prompt: "test input", tone: "neutral" } },
      isPending: false,
    })),
);

vi.mock("@/api/traces/useTracesByIds", () => ({
  default: (params: { traceIds: string[] }) => mockTracesByIds(params),
}));

vi.mock("@/api/traces/useSpansByIds", () => ({
  default: ({ spanIds }: { spanIds: string[] }) =>
    spanIds.map((id) => ({
      data: { id, input: { prompt: "span input" } },
      isPending: false,
    })),
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
    mockDatasetColumns.mockReturnValue(POPULATED_DATASET);
    mockTracesByIds.mockImplementation(({ traceIds }) =>
      traceIds.map((id) => ({
        data: { id, input: { prompt: "test input", tone: "neutral" } },
        isPending: false,
      })),
    );
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

  const traceWithoutInput = {
    ...mockTrace,
    id: "trace-no-input",
    input: undefined as unknown as object,
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
      name: new RegExp(`Select a dataset|${itemName}`),
    });
    fireEvent.click(trigger);
    const items = screen.getAllByText(itemName);
    fireEvent.click(items[items.length - 1]);
  };

  it("should render the dataset dialog when open", () => {
    render(<AddToDatasetDialog {...baseProps} />, { wrapper });

    expect(
      screen.getByRole("button", { name: /Select a dataset/i }),
    ).toBeInTheDocument();
  });

  it("should display enrichment checkboxes when selecting a dataset with traces", () => {
    render(<AddToDatasetDialog {...baseProps} />, { wrapper });

    openDropdownAndSelect("Test Dataset 1");

    expect(screen.getByLabelText("Nested spans")).toBeInTheDocument();
    expect(screen.getByLabelText("Tags")).toBeInTheDocument();
    expect(screen.getByLabelText("Feedback scores")).toBeInTheDocument();
    expect(screen.getByLabelText("Comments")).toBeInTheDocument();
    expect(screen.getByLabelText("Usage metrics")).toBeInTheDocument();
    expect(screen.getByLabelText("Metadata")).toBeInTheDocument();
  });

  it("should have all enrichment checkboxes checked by default", () => {
    render(<AddToDatasetDialog {...baseProps} />, { wrapper });

    openDropdownAndSelect("Test Dataset 1");

    expect(screen.getByLabelText("Nested spans")).toBeChecked();
    expect(screen.getByLabelText("Tags")).toBeChecked();
    expect(screen.getByLabelText("Feedback scores")).toBeChecked();
    expect(screen.getByLabelText("Comments")).toBeChecked();
    expect(screen.getByLabelText("Usage metrics")).toBeChecked();
    expect(screen.getByLabelText("Metadata")).toBeChecked();
  });

  it("should allow unchecking enrichment options", async () => {
    render(<AddToDatasetDialog {...baseProps} />, { wrapper });

    openDropdownAndSelect("Test Dataset 1");

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
      ...baseProps,
      selectedRows: [mockSpan],
    };

    render(<AddToDatasetDialog {...propsWithSpan} />, { wrapper });

    openDropdownAndSelect("Test Dataset 1");

    expect(screen.queryByLabelText("Nested spans")).not.toBeInTheDocument();
    expect(screen.getByLabelText("Tags")).toBeInTheDocument();
    expect(screen.getByLabelText("Feedback scores")).toBeInTheDocument();
    expect(screen.getByLabelText("Comments")).toBeInTheDocument();
    expect(screen.getByLabelText("Usage metrics")).toBeInTheDocument();
    expect(screen.getByLabelText("Metadata")).toBeInTheDocument();
  });

  it("should have all span enrichment checkboxes checked by default", () => {
    const propsWithSpan = {
      ...baseProps,
      selectedRows: [mockSpan],
    };

    render(<AddToDatasetDialog {...propsWithSpan} />, { wrapper });

    openDropdownAndSelect("Test Dataset 1");

    expect(screen.getByLabelText("Tags")).toBeChecked();
    expect(screen.getByLabelText("Feedback scores")).toBeChecked();
    expect(screen.getByLabelText("Comments")).toBeChecked();
    expect(screen.getByLabelText("Usage metrics")).toBeChecked();
    expect(screen.getByLabelText("Metadata")).toBeChecked();
  });

  it("should allow unchecking span enrichment options", async () => {
    const propsWithSpan = {
      ...baseProps,
      selectedRows: [mockSpan],
    };

    render(<AddToDatasetDialog {...propsWithSpan} />, { wrapper });

    openDropdownAndSelect("Test Dataset 1");

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
    render(<AddToDatasetDialog {...baseProps} />, { wrapper });

    const trigger = screen.getByRole("button", {
      name: /Select a dataset/i,
    });
    fireEvent.click(trigger);

    expect(screen.getByText("Test Dataset 1")).toBeInTheDocument();
    expect(screen.getByText("First test dataset")).toBeInTheDocument();
    expect(screen.queryByText("Test Suite 1")).not.toBeInTheDocument();
  });

  it("should display search input in dropdown", () => {
    render(<AddToDatasetDialog {...baseProps} />, { wrapper });

    const trigger = screen.getByRole("button", {
      name: /Select a dataset/i,
    });
    fireEvent.click(trigger);

    const searchInput = screen.getByPlaceholderText("Search datasets");
    expect(searchInput).toBeInTheDocument();
  });

  it("should display add dataset option in dropdown", () => {
    render(<AddToDatasetDialog {...baseProps} />, { wrapper });

    const trigger = screen.getByRole("button", {
      name: /Select a dataset/i,
    });
    fireEvent.click(trigger);

    expect(screen.getByText("Add dataset")).toBeInTheDocument();
  });

  it("should show alert when no valid rows are present", () => {
    const propsWithInvalidRows = {
      ...baseProps,
      selectedRows: [{ ...mockTrace, input: undefined as unknown as object }],
    };

    render(<AddToDatasetDialog {...propsWithInvalidRows} />, { wrapper });

    expect(
      screen.getByText(
        "There are no rows that can be added as dataset items. The input field is missing. Turn on advanced mapping to pick the fields to use instead.",
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

    render(<AddToDatasetDialog {...propsWithPartialValid} />, { wrapper });

    expect(
      screen.getByText(
        "Only rows with input fields will be added as dataset items.",
      ),
    ).toBeInTheDocument();
  });

  it("should keep the dropdown open when no valid rows, so advanced mapping stays reachable", () => {
    const propsWithInvalidRows = {
      ...baseProps,
      selectedRows: [{ ...mockTrace, input: undefined as unknown as object }],
    };

    render(<AddToDatasetDialog {...propsWithInvalidRows} />, { wrapper });

    const trigger = screen.getByRole("button", {
      name: /Select a dataset/i,
    });
    expect(trigger).toBeEnabled();
  });

  it("should call addTracesToDataset mutation when clicking on dataset with only traces", async () => {
    render(<AddToDatasetDialog {...baseProps} />, { wrapper });

    openDropdownAndSelect("Test Dataset 1");

    fireEvent.click(screen.getByRole("button", { name: /^Add \d+ items$/ }));

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
      ...baseProps,
      selectedRows: [mockSpan],
    };

    render(<AddToDatasetDialog {...propsWithSpan} />, { wrapper });

    openDropdownAndSelect("Test Dataset 1");

    fireEvent.click(screen.getByRole("button", { name: /^Add \d+ items$/ }));

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
    render(<AddToDatasetDialog {...baseProps} />, { wrapper });

    openDropdownAndSelect("Test Dataset 1");

    fireEvent.click(screen.getByLabelText("Nested spans"));
    fireEvent.click(screen.getByLabelText("Tags"));
    fireEvent.click(screen.getByLabelText("Usage metrics"));

    fireEvent.click(screen.getByRole("button", { name: /^Add \d+ items$/ }));

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
      ...baseProps,
      selectedRows: [mockSpan],
    };

    render(<AddToDatasetDialog {...propsWithSpan} />, { wrapper });

    openDropdownAndSelect("Test Dataset 1");

    fireEvent.click(screen.getByLabelText("Tags"));
    fireEvent.click(screen.getByLabelText("Comments"));
    fireEvent.click(screen.getByLabelText("Metadata"));

    fireEvent.click(screen.getByRole("button", { name: /^Add \d+ items$/ }));

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
  const enableAdvancedMapping = async () => {
    fireEvent.click(screen.getByRole("switch", { name: /Advanced mapping/i }));
    await screen.findByRole("button", { name: /Add field/i });
  };

  const navigateToPath = (stepsDown: number) => {
    fireEvent.keyDown(document, { key: "ArrowDown" });
    fireEvent.keyDown(document, { key: "ArrowRight" });
    for (let i = 0; i < stepsDown; i += 1) {
      fireEvent.keyDown(document, { key: "ArrowDown" });
    }
    fireEvent.keyDown(document, { key: "Enter" });
  };

  const openRowExplorer = (rowId: string) => {
    const row = screen.getByTestId(`mapping-row-${rowId}`);
    fireEvent.click(within(row).getByTestId("path-source-trigger"));
  };

  const openAddFieldExplorer = () =>
    fireEvent.click(screen.getByRole("button", { name: /Add field/i }));

  const addFieldFromExplorer = (stepsDown: number) => {
    openAddFieldExplorer();
    navigateToPath(stepsDown);
  };

  it("should reach advanced mapping when no row carries an input", async () => {
    render(
      <AddToDatasetDialog {...baseProps} selectedRows={[traceWithoutInput]} />,
      { wrapper },
    );

    openDropdownAndSelect("Test Dataset 1");
    await enableAdvancedMapping();

    openRowExplorer("input");
    fireEvent.keyDown(document, { key: "ArrowDown" });
    fireEvent.keyDown(document, { key: "Enter" });

    fireEvent.click(screen.getByRole("button", { name: /^Add \d+ items$/ }));

    await waitFor(() => {
      expect(mockAddTracesToDataset).toHaveBeenCalledWith(
        expect.objectContaining({
          traceIds: ["trace-no-input"],
          fieldMappings: expect.objectContaining({ expected_output: "output" }),
        }),
        expect.any(Object),
      );
    });
  });

  it("should explain an empty explorer instead of showing a blank popover", async () => {
    mockTracesByIds.mockImplementation(({ traceIds }) =>
      traceIds.map(() => ({ data: undefined, isPending: false })),
    );
    render(<AddToDatasetDialog {...baseProps} />, { wrapper });

    openDropdownAndSelect("Test Dataset 1");
    await enableAdvancedMapping();
    openAddFieldExplorer();

    expect(await screen.findByText(/could not be loaded/i)).toBeInTheDocument();
  });

  it("should say the explorer is loading while the samples are in flight", async () => {
    mockTracesByIds.mockImplementation(({ traceIds }) =>
      traceIds.map(() => ({ data: undefined, isPending: true })),
    );
    render(<AddToDatasetDialog {...baseProps} />, { wrapper });

    openDropdownAndSelect("Test Dataset 1");
    await enableAdvancedMapping();
    openAddFieldExplorer();

    expect(await screen.findByText(/Loading fields/i)).toBeInTheDocument();
  });

  it("should not send field mappings while advanced mapping is off", async () => {
    render(<AddToDatasetDialog {...baseProps} />, { wrapper });

    openDropdownAndSelect("Test Dataset 1");
    fireEvent.click(screen.getByRole("button", { name: /^Add \d+ items$/ }));

    await waitFor(() => {
      expect(mockAddTracesToDataset).toHaveBeenCalledWith(
        expect.objectContaining({ fieldMappings: {} }),
        expect.any(Object),
      );
    });
  });

  it("should send the chosen path for a fixed row without touching enrichment options", async () => {
    render(<AddToDatasetDialog {...baseProps} />, { wrapper });

    openDropdownAndSelect("Test Dataset 1");
    await enableAdvancedMapping();

    openRowExplorer("input");
    fireEvent.keyDown(document, { key: "ArrowDown" });
    fireEvent.keyDown(document, { key: "Enter" });

    fireEvent.click(screen.getByRole("button", { name: /^Add \d+ items$/ }));

    await waitFor(() => {
      expect(mockAddTracesToDataset).toHaveBeenCalledWith(
        expect.objectContaining({
          fieldMappings: {
            input: "input.prompt",
            expected_output: "output",
          },
          enrichmentOptions: {
            include_spans: true,
            include_tags: true,
            include_feedback_scores: true,
            include_comments: true,
            include_usage: true,
            include_metadata: true,
          },
        }),
        expect.any(Object),
      );
    });
  });

  it("should not add a row until a path is picked", async () => {
    render(<AddToDatasetDialog {...baseProps} />, { wrapper });

    openDropdownAndSelect("Test Dataset 1");
    await enableAdvancedMapping();

    openAddFieldExplorer();
    expect(screen.queryByPlaceholderText("Field name")).not.toBeInTheDocument();

    fireEvent.keyDown(document, { key: "Escape" });
    expect(screen.queryByPlaceholderText("Field name")).not.toBeInTheDocument();
  });

  it("should name the added row after the last segment of the picked path", async () => {
    render(<AddToDatasetDialog {...baseProps} />, { wrapper });

    openDropdownAndSelect("Test Dataset 1");
    await enableAdvancedMapping();
    addFieldFromExplorer(2);

    expect(screen.getByPlaceholderText("Field name")).toHaveValue("tone");

    fireEvent.click(screen.getByRole("button", { name: /^Add \d+ items$/ }));

    await waitFor(() => {
      expect(mockAddTracesToDataset).toHaveBeenCalledWith(
        expect.objectContaining({
          fieldMappings: {
            input: "input",
            expected_output: "output",
            tone: "input.tone",
          },
        }),
        expect.any(Object),
      );
    });
  });

  it("should title the explorer by flow", async () => {
    render(<AddToDatasetDialog {...baseProps} />, { wrapper });

    openDropdownAndSelect("Test Dataset 1");
    await enableAdvancedMapping();

    openAddFieldExplorer();
    expect(
      screen.queryByText(/Select a field to map to:/),
    ).not.toBeInTheDocument();

    fireEvent.keyDown(document, { key: "Escape" });

    openRowExplorer("input");

    expect(screen.getByText(/Select a field to map to:/)).toBeInTheDocument();
  });

  it("should block submit while a field name is invalid", async () => {
    render(<AddToDatasetDialog {...baseProps} />, { wrapper });

    openDropdownAndSelect("Test Dataset 1");
    await enableAdvancedMapping();
    addFieldFromExplorer(2);

    fireEvent.change(screen.getByPlaceholderText("Field name"), {
      target: { value: "input" },
    });

    expect(screen.getByText("Field name is already used")).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: /^Add \d+ items$/ }),
    ).toBeDisabled();

    fireEvent.change(screen.getByPlaceholderText("Field name"), {
      target: { value: "tone" },
    });

    expect(
      screen.getByRole("button", { name: /^Add \d+ items$/ }),
    ).toBeEnabled();
  });

  it("should overwrite the mapping when the explorer is re-opened", async () => {
    render(<AddToDatasetDialog {...baseProps} />, { wrapper });

    openDropdownAndSelect("Test Dataset 1");
    await enableAdvancedMapping();
    addFieldFromExplorer(2);

    openRowExplorer("custom-1");

    fireEvent.keyDown(document, { key: "ArrowUp" });
    fireEvent.keyDown(document, { key: "Enter" });

    fireEvent.click(screen.getByRole("button", { name: /^Add \d+ items$/ }));

    await waitFor(() => {
      expect(mockAddTracesToDataset).toHaveBeenCalledWith(
        expect.objectContaining({
          fieldMappings: {
            input: "input",
            expected_output: "output",
            tone: "input.prompt",
          },
        }),
        expect.any(Object),
      );
    });
  });

  it("should re-enable an enrichment option from a quick add chip without adding a mapping", async () => {
    render(<AddToDatasetDialog {...baseProps} />, { wrapper });

    openDropdownAndSelect("Test Dataset 1");
    fireEvent.click(screen.getByLabelText("Tags"));
    await enableAdvancedMapping();

    fireEvent.click(screen.getByText("Tags"));

    fireEvent.click(screen.getByRole("button", { name: /^Add \d+ items$/ }));

    await waitFor(() => {
      expect(mockAddTracesToDataset).toHaveBeenCalledWith(
        expect.objectContaining({
          fieldMappings: { input: "input", expected_output: "output" },
          enrichmentOptions: expect.objectContaining({ include_tags: true }),
        }),
        expect.any(Object),
      );
    });
  });

  it("should send field mappings for spans too", async () => {
    render(<AddToDatasetDialog {...baseProps} selectedRows={[mockSpan]} />, {
      wrapper,
    });

    openDropdownAndSelect("Test Dataset 1");
    await enableAdvancedMapping();

    openRowExplorer("expected_output");
    navigateToPath(1);

    fireEvent.click(screen.getByRole("button", { name: /^Add \d+ items$/ }));

    await waitFor(() => {
      expect(mockAddSpansToDataset).toHaveBeenCalledWith(
        expect.objectContaining({
          fieldMappings: {
            input: "input",
            expected_output: "input.prompt",
          },
        }),
        expect.any(Object),
      );
    });
  });

  it("should not show the preview in basic mode", () => {
    render(<AddToDatasetDialog {...baseProps} />, { wrapper });

    openDropdownAndSelect("Test Dataset 1");

    expect(screen.queryByText("Preview")).not.toBeInTheDocument();
    expect(screen.queryByText(/^Adding \d+ field/)).not.toBeInTheDocument();
  });

  it("should preview one row per sampled entity with a column per mapping", async () => {
    render(<AddToDatasetDialog {...baseProps} />, { wrapper });

    openDropdownAndSelect("Test Dataset 1");
    await enableAdvancedMapping();

    expect(screen.getByText("Preview")).toBeInTheDocument();
    expect(
      screen.getByRole("columnheader", { name: "input" }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("columnheader", { name: "expected_output" }),
    ).toBeInTheDocument();
    expect(screen.getAllByRole("row")).toHaveLength(2);
    expect(
      screen.getByRole("cell", {
        name: '{"prompt":"test input","tone":"neutral"}',
      }),
    ).toBeInTheDocument();
  });

  it("should mark unresolved paths as empty and nested spans as deferred", async () => {
    render(<AddToDatasetDialog {...baseProps} />, { wrapper });

    openDropdownAndSelect("Test Dataset 1");
    await enableAdvancedMapping();

    const expectedOutput = screen.getByRole("columnheader", {
      name: "expected_output",
    });
    const columnIndex = Array.from(
      expectedOutput.parentElement!.children,
    ).indexOf(expectedOutput);
    const dataRow = screen.getAllByRole("row")[1];

    expect(dataRow.children[columnIndex]).toHaveTextContent("Empty");
    expect(screen.getByText("Added on import")).toBeInTheDocument();
  });

  it("should add a preview column when a field is added", async () => {
    render(<AddToDatasetDialog {...baseProps} />, { wrapper });

    openDropdownAndSelect("Test Dataset 1");
    await enableAdvancedMapping();
    addFieldFromExplorer(2);

    expect(
      screen.getByRole("columnheader", { name: "tone" }),
    ).toBeInTheDocument();
    expect(screen.getByRole("cell", { name: "neutral" })).toBeInTheDocument();
  });

  it("should summarise the mapping in the footer", async () => {
    render(<AddToDatasetDialog {...baseProps} />, { wrapper });

    openDropdownAndSelect("Test Dataset 1");
    await enableAdvancedMapping();

    expect(screen.getByText(/^Adding 8 fields/)).toHaveTextContent(
      /fields are empty for some traces$/,
    );
  });

  it("should offer the dataset's existing columns as chips", async () => {
    render(<AddToDatasetDialog {...baseProps} />, { wrapper });

    openDropdownAndSelect("Test Dataset 1");
    await enableAdvancedMapping();

    const chips = within(screen.getByTestId("quick-add-chips"));

    expect(chips.getByText("bucket")).toBeInTheDocument();
    expect(chips.getByText("tone")).toBeInTheDocument();
    expect(chips.queryByText("input")).not.toBeInTheDocument();
    expect(chips.queryByText("expected_output")).not.toBeInTheDocument();
    expect(chips.queryByText("metadata")).not.toBeInTheDocument();
  });

  it("should add a row named after the dataset column and map it", async () => {
    render(<AddToDatasetDialog {...baseProps} />, { wrapper });

    openDropdownAndSelect("Test Dataset 1");
    await enableAdvancedMapping();

    fireEvent.click(
      within(screen.getByTestId("quick-add-chips")).getByText("bucket"),
    );
    navigateToPath(2);

    fireEvent.click(screen.getByRole("button", { name: /^Add \d+ items$/ }));

    await waitFor(() => {
      expect(mockAddTracesToDataset).toHaveBeenCalledWith(
        expect.objectContaining({
          fieldMappings: {
            input: "input",
            expected_output: "output",
            bucket: "input.tone",
          },
        }),
        expect.any(Object),
      );
    });
  });

  it("should warn that a brand new column is empty for the existing items", async () => {
    render(<AddToDatasetDialog {...baseProps} />, { wrapper });

    openDropdownAndSelect("Test Dataset 1");
    await enableAdvancedMapping();
    addFieldFromExplorer(1);

    const warning = (content: string, element: Element | null) =>
      element?.tagName === "SPAN" &&
      /the 412 items already in this dataset/.test(element.textContent ?? "");

    expect(screen.getByText(warning)).toBeInTheDocument();

    fireEvent.change(screen.getByPlaceholderText("Field name"), {
      target: { value: "bucket" },
    });

    expect(screen.queryByText(warning)).not.toBeInTheDocument();
  });

  it("should not warn when the dataset has no items yet", async () => {
    mockDatasetColumns.mockReturnValue(EMPTY_DATASET);
    render(<AddToDatasetDialog {...baseProps} />, { wrapper });

    openDropdownAndSelect("Test Dataset 1");
    await enableAdvancedMapping();
    addFieldFromExplorer(1);

    expect(
      screen.queryByText(
        (content, element) =>
          element?.tagName === "SPAN" &&
          /already in this dataset/.test(element.textContent ?? ""),
      ),
    ).not.toBeInTheDocument();
  });

  it("should block submit while a chip-added row has no source", async () => {
    render(<AddToDatasetDialog {...baseProps} />, { wrapper });

    openDropdownAndSelect("Test Dataset 1");
    await enableAdvancedMapping();

    fireEvent.click(
      within(screen.getByTestId("quick-add-chips")).getByText("bucket"),
    );
    fireEvent.keyDown(document, { key: "Escape" });

    expect(screen.getByText("Select a field to map")).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: /^Add \d+ items$/ }),
    ).toBeDisabled();

    openRowExplorer("custom-1");
    navigateToPath(2);

    expect(screen.queryByText("Select a field to map")).not.toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: /^Add \d+ items$/ }),
    ).toBeEnabled();
  });

  it("keeps rows without an input field out of basic mode", () => {
    render(
      <AddToDatasetDialog {...baseProps} selectedRows={[traceWithoutInput]} />,
      { wrapper },
    );

    expect(
      screen.getByText(/There are no rows that can be added/),
    ).toBeInTheDocument();
  });

  it("stops filtering on the input field once advanced mapping is on", async () => {
    render(
      <AddToDatasetDialog
        {...baseProps}
        selectedRows={[mockTrace, traceWithoutInput]}
      />,
      { wrapper },
    );

    openDropdownAndSelect("Test Dataset 1");
    expect(screen.getByText(/Only rows with input fields/)).toBeInTheDocument();

    await enableAdvancedMapping();

    expect(
      screen.queryByText(/Only rows with input fields/),
    ).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: /^Add \d+ items$/ }));

    await waitFor(() => {
      expect(mockAddTracesToDataset).toHaveBeenCalledWith(
        expect.objectContaining({
          traceIds: ["trace-1", "trace-no-input"],
        }),
        expect.any(Object),
      );
    });
  });
});
