import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import AnnotateTracesDialog from "./AnnotateTracesDialog";
import { Span, Trace } from "@/types/traces";
import { TRACE_DATA_TYPE } from "@/hooks/useTracesOrSpansList";
import { FEEDBACK_DEFINITION_TYPE } from "@/types/feedback-definitions";

const mockScoreBatch = vi.fn();
const mockToast = vi.fn();
const mockSetOpen = vi.fn();
const mockRefetchDefinitions = vi.fn();
const mockUseFeedbackDefinitionsList = vi.fn();

vi.mock("@/api/feedback-definitions/useFeedbackDefinitionsList", () => ({
  default: (...args: unknown[]) => mockUseFeedbackDefinitionsList(...args),
}));

vi.mock("@/api/traces/useFeedbackScoresBatchMutation", () => ({
  default: vi.fn(() => ({
    mutateAsync: mockScoreBatch,
    isPending: false,
  })),
}));

vi.mock("@/store/AppStore", () => ({
  default: vi.fn((selector) =>
    selector({
      activeWorkspaceName: "test-workspace",
      activeProjectId: "test-project-id",
      loggedInUserName: "tester",
    }),
  ),
}));

vi.mock("@/shared/SelectBox/SelectBox", () => ({
  default: ({
    options,
    onChange,
    testId,
  }: {
    options: Array<{ label: string; value: string }>;
    onChange: (value?: string) => void;
    testId?: string;
  }) => (
    <div data-testid={testId}>
      {options.map((option) => (
        <button
          key={option.value}
          type="button"
          data-testid={`${testId}-option-${option.value}`}
          onClick={() => onChange(option.value)}
        >
          {option.label}
        </button>
      ))}
    </div>
  ),
}));

vi.mock("@/ui/use-toast", () => ({
  useToast: vi.fn(() => ({ toast: mockToast })),
}));

const mockTraces: Trace[] = [
  { id: "trace-1" } as Trace,
  { id: "trace-2" } as Trace,
];

const renderDialog = (
  rows: Array<Trace | Span> = mockTraces,
  type: TRACE_DATA_TYPE = TRACE_DATA_TYPE.traces,
) => {
  render(
    <AnnotateTracesDialog
      rows={rows}
      open={true}
      setOpen={mockSetOpen}
      type={type}
    />,
  );
};

const selectNumericalScore = async () => {
  fireEvent.click(
    screen.getByTestId("annotate-bulk-score-select-option-helpfulness"),
  );
  fireEvent.change(screen.getByTestId("annotate-bulk-score-input"), {
    target: { value: "7" },
  });
  await waitFor(() => {
    expect(screen.getByTestId("annotate-bulk-apply-button")).toBeEnabled();
  });
};

describe("AnnotateTracesDialog", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockUseFeedbackDefinitionsList.mockReturnValue({
      data: {
        content: [
          {
            name: "helpfulness",
            type: FEEDBACK_DEFINITION_TYPE.numerical,
            details: { min: 0, max: 10 },
          },
          {
            name: "satisfaction",
            type: FEEDBACK_DEFINITION_TYPE.categorical,
            details: { categories: { good: 1, bad: 0 } },
          },
          {
            name: "thumbs",
            type: FEEDBACK_DEFINITION_TYPE.boolean,
            details: { true_label: "up", false_label: "down" },
          },
        ],
        total: 3,
      },
      isLoading: false,
      isError: false,
      refetch: mockRefetchDefinitions,
    });
  });

  it("renders the dialog with selection count and disabled apply button", () => {
    renderDialog();

    expect(screen.getByText("Annotate traces")).toBeInTheDocument();
    expect(screen.getByText(/2 selected/)).toBeInTheDocument();
    const applyButton = screen.getByTestId("annotate-bulk-apply-button");
    expect(applyButton).toBeDisabled();
  });

  it("enables apply after selecting a score and entering a valid value", async () => {
    renderDialog();
    await selectNumericalScore();
  });

  it("submits a batch annotation for every selected row", async () => {
    mockScoreBatch.mockResolvedValue({});
    renderDialog();
    await selectNumericalScore();

    fireEvent.click(screen.getByTestId("annotate-bulk-apply-button"));

    await waitFor(() => {
      expect(mockScoreBatch).toHaveBeenCalledTimes(1);
    });

    expect(mockScoreBatch).toHaveBeenCalledWith({
      scores: [
        {
          id: "trace-1",
          name: "helpfulness",
          value: 7,
          categoryName: undefined,
          reason: undefined,
        },
        {
          id: "trace-2",
          name: "helpfulness",
          value: 7,
          categoryName: undefined,
          reason: undefined,
        },
      ],
      isSpanType: false,
    });
    expect(mockToast).toHaveBeenCalledWith(
      expect.objectContaining({ title: "Annotations applied" }),
    );
  });

  it("preserves the entered reason when switching scores", async () => {
    renderDialog();
    await selectNumericalScore();

    fireEvent.change(screen.getByTestId("annotate-bulk-reason-input"), {
      target: { value: "looks good" },
    });
    fireEvent.click(
      screen.getByTestId("annotate-bulk-score-select-option-satisfaction"),
    );

    expect(screen.getByTestId("annotate-bulk-reason-input")).toHaveValue(
      "looks good",
    );
    await waitFor(() => {
      expect(screen.getByTestId("annotate-bulk-apply-button")).toBeDisabled();
    });
  });

  it("disables apply and skips submission for out-of-range values", async () => {
    renderDialog();

    fireEvent.click(
      screen.getByTestId("annotate-bulk-score-select-option-helpfulness"),
    );
    fireEvent.change(screen.getByTestId("annotate-bulk-score-input"), {
      target: { value: "99" },
    });

    expect(screen.getByTestId("annotate-bulk-apply-button")).toBeDisabled();

    fireEvent.click(screen.getByTestId("annotate-bulk-apply-button"));
    expect(mockScoreBatch).not.toHaveBeenCalled();
  });

  it("supports categorical scores via category selection", async () => {
    mockScoreBatch.mockResolvedValue({});
    renderDialog();

    fireEvent.click(
      screen.getByTestId("annotate-bulk-score-select-option-satisfaction"),
    );
    fireEvent.click(screen.getByTestId("annotate-bulk-category-toggle-good"));

    await waitFor(() => {
      expect(screen.getByTestId("annotate-bulk-apply-button")).toBeEnabled();
    });

    fireEvent.click(screen.getByTestId("annotate-bulk-apply-button"));

    await waitFor(() => {
      expect(mockScoreBatch).toHaveBeenCalledWith({
        scores: expect.arrayContaining([
          expect.objectContaining({
            name: "satisfaction",
            categoryName: "good",
            value: 1,
          }),
        ]),
        isSpanType: false,
      });
    });
  });

  it("maps boolean scores to their labels and numeric values", async () => {
    mockScoreBatch.mockResolvedValue({});
    renderDialog();

    fireEvent.click(
      screen.getByTestId("annotate-bulk-score-select-option-thumbs"),
    );
    fireEvent.click(screen.getByText("up"));

    await waitFor(() => {
      expect(screen.getByTestId("annotate-bulk-apply-button")).toBeEnabled();
    });

    fireEvent.click(screen.getByTestId("annotate-bulk-apply-button"));

    await waitFor(() => {
      expect(mockScoreBatch).toHaveBeenCalledWith({
        scores: expect.arrayContaining([
          expect.objectContaining({
            name: "thumbs",
            value: 1,
            categoryName: "up",
          }),
        ]),
        isSpanType: false,
      });
    });
  });

  it("submits batch span annotations with isSpanType: true", async () => {
    mockScoreBatch.mockResolvedValue({});
    const spans: Span[] = [
      { id: "span-1", trace_id: "trace-1" } as Span,
      { id: "span-2", trace_id: "trace-1" } as Span,
    ];
    renderDialog(spans, TRACE_DATA_TYPE.spans);

    expect(screen.getByText("Annotate spans")).toBeInTheDocument();
    await selectNumericalScore();

    fireEvent.click(screen.getByTestId("annotate-bulk-apply-button"));

    await waitFor(() => {
      expect(mockScoreBatch).toHaveBeenCalledTimes(1);
    });

    expect(mockScoreBatch).toHaveBeenCalledWith({
      scores: [
        expect.objectContaining({ id: "span-1" }),
        expect.objectContaining({ id: "span-2" }),
      ],
      isSpanType: true,
    });
  });

  it("keeps dialog open when batch mutation is rejected", async () => {
    mockScoreBatch.mockRejectedValue(new Error("Network failure"));
    renderDialog();
    await selectNumericalScore();

    fireEvent.click(screen.getByTestId("annotate-bulk-apply-button"));

    await waitFor(() => {
      expect(mockScoreBatch).toHaveBeenCalledTimes(1);
    });
    expect(mockSetOpen).not.toHaveBeenCalledWith(false);
  });

  it("renders loading state when feedback definitions are loading", () => {
    mockUseFeedbackDefinitionsList.mockReturnValue({
      data: undefined,
      isLoading: true,
      isError: false,
      refetch: mockRefetchDefinitions,
    });
    renderDialog();

    expect(
      screen.getByText("Loading feedback definitions…"),
    ).toBeInTheDocument();
  });

  it("renders error state and retries on button click when definitions query fails", () => {
    mockUseFeedbackDefinitionsList.mockReturnValue({
      data: undefined,
      isLoading: false,
      isError: true,
      refetch: mockRefetchDefinitions,
    });
    renderDialog();

    expect(
      screen.getByText(/Failed to load feedback definitions/),
    ).toBeInTheDocument();
    const retryButton = screen.getByRole("button", { name: "Retry" });
    fireEvent.click(retryButton);
    expect(mockRefetchDefinitions).toHaveBeenCalledTimes(1);
  });

  it("shows destructive toast and closes dialog when rows exceed 500", () => {
    const manyRows: Trace[] = Array.from({ length: 501 }, (_, i) => ({
      id: `trace-${i}`,
    })) as Trace[];
    renderDialog(manyRows);

    expect(mockToast).toHaveBeenCalledWith(
      expect.objectContaining({
        title: "Error",
        description:
          "You can only annotate up to 500 traces at a time. Please select fewer items.",
        variant: "destructive",
      }),
    );
    expect(mockSetOpen).toHaveBeenCalledWith(false);
  });
});
