import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import AnnotateTracesDialog from "./AnnotateTracesDialog";
import { Span, Trace } from "@/types/traces";
import { TRACE_DATA_TYPE } from "@/hooks/useTracesOrSpansList";
import { FEEDBACK_DEFINITION_TYPE } from "@/types/feedback-definitions";

const mockSetFeedbackScore = vi.fn();
const mockToast = vi.fn();
const mockSetOpen = vi.fn();

vi.mock("@/api/feedback-definitions/useFeedbackDefinitionsList", () => ({
  default: vi.fn(() => ({
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
    isPending: false,
  })),
}));

vi.mock("@/api/traces/useTraceFeedbackScoreSetMutation", () => ({
  default: vi.fn(() => ({
    mutateAsync: mockSetFeedbackScore,
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

  it("submits an annotation for every selected row", async () => {
    mockSetFeedbackScore.mockResolvedValue({});
    renderDialog();
    await selectNumericalScore();

    fireEvent.click(screen.getByTestId("annotate-bulk-apply-button"));

    await waitFor(() => {
      expect(mockSetFeedbackScore).toHaveBeenCalledTimes(2);
    });

    expect(mockSetFeedbackScore).toHaveBeenCalledWith(
      expect.objectContaining({
        name: "helpfulness",
        value: 7,
        traceId: "trace-1",
      }),
    );
    expect(mockSetFeedbackScore).toHaveBeenCalledWith(
      expect.objectContaining({
        name: "helpfulness",
        value: 7,
        traceId: "trace-2",
      }),
    );
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
    expect(screen.getByTestId("annotate-bulk-apply-button")).toBeDisabled();
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
    expect(mockSetFeedbackScore).not.toHaveBeenCalled();
  });

  it("supports categorical scores via category selection", async () => {
    mockSetFeedbackScore.mockResolvedValue({});
    renderDialog();

    fireEvent.click(
      screen.getByTestId("annotate-bulk-score-select-option-satisfaction"),
    );
    fireEvent.click(
      screen.getByTestId("annotate-bulk-category-select-option-good"),
    );

    await waitFor(() => {
      expect(screen.getByTestId("annotate-bulk-apply-button")).toBeEnabled();
    });

    fireEvent.click(screen.getByTestId("annotate-bulk-apply-button"));

    await waitFor(() => {
      expect(mockSetFeedbackScore).toHaveBeenCalledWith(
        expect.objectContaining({
          name: "satisfaction",
          categoryName: "good",
          value: 1,
        }),
      );
    });
  });

  it("maps boolean scores to their labels and numeric values", async () => {
    mockSetFeedbackScore.mockResolvedValue({});
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
      expect(mockSetFeedbackScore).toHaveBeenCalledWith(
        expect.objectContaining({
          name: "thumbs",
          value: 1,
          categoryName: "up",
        }),
      );
    });
  });

  it("fans out span annotations with spanId and parent traceId", async () => {
    mockSetFeedbackScore.mockResolvedValue({});
    const spans: Span[] = [
      { id: "span-1", trace_id: "trace-1" } as Span,
      { id: "span-2", trace_id: "trace-1" } as Span,
    ];
    renderDialog(spans, TRACE_DATA_TYPE.spans);

    expect(screen.getByText("Annotate spans")).toBeInTheDocument();
    await selectNumericalScore();

    fireEvent.click(screen.getByTestId("annotate-bulk-apply-button"));

    await waitFor(() => {
      expect(mockSetFeedbackScore).toHaveBeenCalledTimes(2);
    });

    expect(mockSetFeedbackScore).toHaveBeenCalledWith(
      expect.objectContaining({
        traceId: "trace-1",
        spanId: "span-1",
      }),
    );
    expect(mockSetFeedbackScore).toHaveBeenCalledWith(
      expect.objectContaining({
        traceId: "trace-1",
        spanId: "span-2",
      }),
    );
  });

  it("toasts a full failure when every mutation is rejected", async () => {
    mockSetFeedbackScore.mockRejectedValue(new Error("boom"));
    renderDialog();
    await selectNumericalScore();

    fireEvent.click(screen.getByTestId("annotate-bulk-apply-button"));

    await waitFor(() => {
      expect(mockToast).toHaveBeenCalledWith(
        expect.objectContaining({ title: "Error" }),
      );
    });
    expect(mockSetOpen).not.toHaveBeenCalledWith(false);
  });

  it("toasts a partial failure with the success count", async () => {
    mockSetFeedbackScore
      .mockResolvedValueOnce({})
      .mockRejectedValueOnce(new Error("boom"));
    renderDialog();
    await selectNumericalScore();

    fireEvent.click(screen.getByTestId("annotate-bulk-apply-button"));

    await waitFor(() => {
      expect(mockToast).toHaveBeenCalledWith(
        expect.objectContaining({ title: "Partially applied" }),
      );
    });
    expect(mockToast).toHaveBeenCalledWith(
      expect.objectContaining({
        description: "1 of 2 traces annotated successfully.",
      }),
    );
  });
});
