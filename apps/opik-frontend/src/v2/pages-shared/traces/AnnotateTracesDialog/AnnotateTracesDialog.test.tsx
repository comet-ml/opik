import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";

import AnnotateTracesDialog from "./AnnotateTracesDialog";
import { Trace } from "@/types/traces";
import { TRACE_DATA_TYPE } from "@/hooks/useTracesOrSpansList";
import { FEEDBACK_DEFINITION_TYPE } from "@/types/feedback-definitions";

const mockSetFeedbackScore = vi.fn();

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
    "data-testid": testId,
  }: {
    options: Array<{ label: string; value: string }>;
    onChange: (value?: string) => void;
    "data-testid"?: string;
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
  useToast: vi.fn(() => ({ toast: vi.fn() })),
}));

const mockTraces: Trace[] = [
  { id: "trace-1" } as Trace,
  { id: "trace-2" } as Trace,
];

const renderDialog = () => {
  const setOpen = vi.fn();
  render(
    <AnnotateTracesDialog
      rows={mockTraces}
      open={true}
      setOpen={setOpen}
      type={TRACE_DATA_TYPE.traces}
    />,
  );
  return setOpen;
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

    fireEvent.click(
      screen.getByTestId("annotate-bulk-score-select-option-helpfulness"),
    );

    const input = screen.getByTestId("annotate-bulk-score-input");
    fireEvent.change(input, { target: { value: "7" } });

    await waitFor(() => {
      expect(screen.getByTestId("annotate-bulk-apply-button")).toBeEnabled();
    });
  });

  it("submits an annotation for every selected row", async () => {
    mockSetFeedbackScore.mockResolvedValue({});
    renderDialog();

    fireEvent.click(
      screen.getByTestId("annotate-bulk-score-select-option-helpfulness"),
    );
    fireEvent.change(screen.getByTestId("annotate-bulk-score-input"), {
      target: { value: "7" },
    });

    await waitFor(() => {
      expect(screen.getByTestId("annotate-bulk-apply-button")).toBeEnabled();
    });

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
});
