import { ReactNode } from "react";
import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { TooltipProvider } from "@/ui/tooltip";
import AnnotateRow from "./AnnotateRow";
import {
  FEEDBACK_DEFINITION_TYPE,
  FeedbackDefinition,
} from "@/types/feedback-definitions";
import { FEEDBACK_SCORE_TYPE } from "@/types/traces";

vi.mock("@/store/AppStore", () => ({
  useLoggedInUserNameOrOpenSourceDefaultUser: vi.fn(() => "tester"),
}));

vi.mock("@/ui/use-toast", () => ({
  useToast: vi.fn(() => ({ toast: vi.fn() })),
}));

const numericalDef: FeedbackDefinition = {
  id: "def-1",
  name: "helpfulness",
  type: FEEDBACK_DEFINITION_TYPE.numerical,
  details: { min: 0, max: 10 },
  created_at: "",
  last_updated_at: "",
};

const booleanDef: FeedbackDefinition = {
  id: "def-2",
  name: "thumbs",
  type: FEEDBACK_DEFINITION_TYPE.boolean,
  details: { true_label: "up", false_label: "down" },
  created_at: "",
  last_updated_at: "",
};

describe("AnnotateRow", () => {
  let queryClient: QueryClient;
  const onUpdateFeedbackScore = vi.fn();
  const onDeleteFeedbackScore = vi.fn();

  beforeEach(() => {
    queryClient = new QueryClient({
      defaultOptions: {
        queries: { retry: false },
        mutations: { retry: false },
      },
    });
    vi.clearAllMocks();
  });

  const renderWithWrapper = (ui: React.ReactElement) => {
    return render(ui, {
      wrapper: ({ children }: { children: ReactNode }) => (
        <QueryClientProvider client={queryClient}>
          <TooltipProvider>{children}</TooltipProvider>
        </QueryClientProvider>
      ),
    });
  };

  it("calls onUpdateFeedbackScore when a valid numeric score is entered", async () => {
    renderWithWrapper(
      <AnnotateRow
        name="helpfulness"
        feedbackDefinition={numericalDef}
        feedbackScore={{
          name: "helpfulness",
          value: 5,
          source: FEEDBACK_SCORE_TYPE.ui,
        }}
        onUpdateFeedbackScore={onUpdateFeedbackScore}
        onDeleteFeedbackScore={onDeleteFeedbackScore}
      />,
    );

    const input = screen.getByTestId("annotate-score-input");
    fireEvent.change(input, { target: { value: "8" } });

    await waitFor(() => {
      expect(onUpdateFeedbackScore).toHaveBeenCalledWith(
        expect.objectContaining({
          name: "helpfulness",
          value: 8,
        }),
      );
    });
    expect(onDeleteFeedbackScore).not.toHaveBeenCalled();
  });

  it("does NOT call onDeleteFeedbackScore when an invalid out-of-range numeric score is entered", async () => {
    renderWithWrapper(
      <AnnotateRow
        name="helpfulness"
        feedbackDefinition={numericalDef}
        feedbackScore={{
          name: "helpfulness",
          value: 5,
          source: FEEDBACK_SCORE_TYPE.ui,
        }}
        onUpdateFeedbackScore={onUpdateFeedbackScore}
        onDeleteFeedbackScore={onDeleteFeedbackScore}
      />,
    );

    const input = screen.getByTestId("annotate-score-input");
    fireEvent.change(input, { target: { value: "99" } });

    // Wait past debounce time
    await new Promise((r) => setTimeout(r, 600));

    expect(onDeleteFeedbackScore).not.toHaveBeenCalled();
    expect(onUpdateFeedbackScore).not.toHaveBeenCalledWith(
      expect.objectContaining({ value: 99 }),
    );
  });

  it("calls onDeleteFeedbackScore when the numeric score is cleared", async () => {
    renderWithWrapper(
      <AnnotateRow
        name="helpfulness"
        feedbackDefinition={numericalDef}
        feedbackScore={{
          name: "helpfulness",
          value: 5,
          source: FEEDBACK_SCORE_TYPE.ui,
        }}
        onUpdateFeedbackScore={onUpdateFeedbackScore}
        onDeleteFeedbackScore={onDeleteFeedbackScore}
      />,
    );

    const input = screen.getByTestId("annotate-score-input");
    fireEvent.change(input, { target: { value: "" } });

    await waitFor(() => {
      expect(onDeleteFeedbackScore).toHaveBeenCalledWith("helpfulness");
    });
  });

  it("calls onUpdateFeedbackScore when boolean option is clicked", () => {
    renderWithWrapper(
      <AnnotateRow
        name="thumbs"
        feedbackDefinition={booleanDef}
        onUpdateFeedbackScore={onUpdateFeedbackScore}
        onDeleteFeedbackScore={onDeleteFeedbackScore}
      />,
    );

    fireEvent.click(screen.getByTestId("annotate-boolean-toggle-up"));

    expect(onUpdateFeedbackScore).toHaveBeenCalledWith(
      expect.objectContaining({
        name: "thumbs",
        value: 1,
        categoryName: "up",
      }),
    );
  });
});
