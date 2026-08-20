import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ReactNode } from "react";
import { TooltipProvider } from "@/ui/tooltip";
import { TRACE_DATA_TYPE } from "@/hooks/useTracesOrSpansList";
import { Span, Trace } from "@/types/traces";
import { UpdateFeedbackScoreData } from "@/v2/pages-shared/traces/TraceDetailsPanel/TraceAnnotateViewer/types";
import AddAnnotationDialog from "./AddAnnotationDialog";

const mockSetTraceFeedbackScores = vi.fn();
const mockSetSpanFeedbackScores = vi.fn();

vi.mock("@/api/traces/useTraceFeedbackScoreBatchSetMutation", () => ({
  default: () => ({
    mutateAsync: mockSetTraceFeedbackScores,
    isPending: false,
  }),
}));
vi.mock("@/api/traces/useSpanFeedbackScoreBatchSetMutation", () => ({
  default: () => ({
    mutateAsync: mockSetSpanFeedbackScores,
    isPending: false,
  }),
}));

vi.mock("@/ui/use-toast", () => ({
  useToast: () => ({ toast: vi.fn() }),
}));

type StubFeedbackScoresEditorProps = {
  onUpdateFeedbackScore: (update: UpdateFeedbackScoreData) => void;
  onDeleteFeedbackScore: (name: string) => void;
};

vi.mock(
  "@/v2/pages-shared/traces/FeedbackScoresEditor/FeedbackScoresEditor",
  () => {
    const Stub = ({
      onUpdateFeedbackScore,
      onDeleteFeedbackScore,
    }: StubFeedbackScoresEditorProps) => (
      <div data-testid="feedback-scores-editor">
        <button
          onClick={() =>
            onUpdateFeedbackScore({
              name: "Relevance",
              value: 1,
              categoryName: "Yes",
              reason: "looks good",
            })
          }
        >
          set-score
        </button>
        <button onClick={() => onDeleteFeedbackScore("Relevance")}>
          clear-score
        </button>
      </div>
    );
    Stub.displayName = "FeedbackScoresEditorStub";
    const StubHeader = () => null;
    StubHeader.displayName = "FeedbackScoresEditorHeaderStub";
    const StubFooter = () => null;
    StubFooter.displayName = "FeedbackScoresEditorFooterStub";
    Stub.Header = StubHeader;
    Stub.Footer = StubFooter;
    return { default: Stub };
  },
);

describe("AddAnnotationDialog", () => {
  let queryClient: QueryClient;

  beforeEach(() => {
    queryClient = new QueryClient({
      defaultOptions: {
        queries: { retry: false },
        mutations: { retry: false },
      },
    });
    vi.clearAllMocks();
    mockSetTraceFeedbackScores.mockResolvedValue(undefined);
    mockSetSpanFeedbackScores.mockResolvedValue(undefined);
  });

  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>
      <TooltipProvider>{children}</TooltipProvider>
    </QueryClientProvider>
  );

  const mockTrace: Trace = {
    id: "row-1",
    name: "Test Trace",
    input: { prompt: "test input" },
    output: { response: "test output" },
    start_time: "2024-01-01T00:00:00Z",
    end_time: "2024-01-01T00:00:01Z",
    duration: 1000,
    created_at: "2024-01-01T00:00:00Z",
    last_updated_at: "2024-01-01T00:00:01Z",
    tags: [],
    metadata: {},
    feedback_scores: [],
    comments: [],
    project_id: "project-1",
  };

  const ROWS: Array<Trace | Span> = [mockTrace, { ...mockTrace, id: "row-2" }];

  const renderDialog = (type: TRACE_DATA_TYPE) =>
    render(
      <AddAnnotationDialog
        rows={ROWS}
        open
        setOpen={vi.fn()}
        projectName="project-name"
        type={type}
      />,
      { wrapper },
    );

  const EXPECTED_SCORES = [
    {
      id: "row-1",
      name: "Relevance",
      value: 1,
      categoryName: "Yes",
      reason: "looks good",
    },
    {
      id: "row-2",
      name: "Relevance",
      value: 1,
      categoryName: "Yes",
      reason: "looks good",
    },
  ];

  it("sends one batch of trace scores, carrying the project name", async () => {
    renderDialog(TRACE_DATA_TYPE.traces);

    fireEvent.click(screen.getByRole("button", { name: "set-score" }));
    fireEvent.click(screen.getByTestId("apply-annotation-button"));

    await waitFor(() =>
      expect(mockSetTraceFeedbackScores).toHaveBeenCalledTimes(1),
    );
    expect(mockSetTraceFeedbackScores).toHaveBeenCalledWith({
      projectName: "project-name",
      scores: EXPECTED_SCORES,
    });
    expect(mockSetSpanFeedbackScores).not.toHaveBeenCalled();
  });

  it("routes span selections to the spans endpoint, not the traces one", async () => {
    renderDialog(TRACE_DATA_TYPE.spans);

    fireEvent.click(screen.getByRole("button", { name: "set-score" }));
    fireEvent.click(screen.getByTestId("apply-annotation-button"));

    await waitFor(() =>
      expect(mockSetSpanFeedbackScores).toHaveBeenCalledTimes(1),
    );
    expect(mockSetSpanFeedbackScores).toHaveBeenCalledWith({
      projectName: "project-name",
      scores: EXPECTED_SCORES,
    });
    expect(mockSetTraceFeedbackScores).not.toHaveBeenCalled();
  });

  it("enables apply only while at least one score is set", () => {
    renderDialog(TRACE_DATA_TYPE.traces);

    expect(screen.getByTestId("apply-annotation-button")).toBeDisabled();

    fireEvent.click(screen.getByRole("button", { name: "set-score" }));
    expect(screen.getByTestId("apply-annotation-button")).toBeEnabled();

    fireEvent.click(screen.getByRole("button", { name: "clear-score" }));
    expect(screen.getByTestId("apply-annotation-button")).toBeDisabled();
  });
});
