import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ReactNode } from "react";
import { PermissionsProvider } from "@/contexts/PermissionsContext";
import { DEFAULT_PERMISSIONS } from "@/types/permissions";
import { TooltipProvider } from "@/ui/tooltip";
import ThreadAnnotatePanel from "./ThreadAnnotatePanel";
import { DetailsActionSection } from "@/v2/pages-shared/traces/DetailsActionSection";

const mockCreateMutate = vi.fn();
const mockBatchDeleteMutate = vi.fn();
const mockUpdateMutate = vi.fn();
const mockSetFeedbackScoreMutate = vi.fn();
const mockDeleteFeedbackScoreMutate = vi.fn();

vi.mock("@/api/traces/useCreateThreadCommentMutation", () => ({
  default: () => ({ mutate: mockCreateMutate }),
}));
vi.mock("@/api/traces/useThreadCommentsBatchDeleteMutation", () => ({
  default: () => ({ mutate: mockBatchDeleteMutate }),
}));
vi.mock("@/api/traces/useUpdateThreadCommentMutation", () => ({
  default: () => ({ mutate: mockUpdateMutate }),
}));
vi.mock("@/api/traces/useThreadFeedbackScoreSetMutation", () => ({
  default: () => ({ mutate: mockSetFeedbackScoreMutate }),
}));
vi.mock("@/api/traces/useThreadFeedbackScoreDeleteMutation", () => ({
  default: () => ({ mutate: mockDeleteFeedbackScoreMutate }),
}));

vi.mock("@/store/AppStore", () => ({
  default: vi.fn((selector) =>
    selector({
      activeWorkspaceName: "test-workspace",
      activeProjectId: "test-project-id",
    }),
  ),
  useLoggedInUserName: () => "tester",
}));

vi.mock("@/ui/use-toast", () => ({
  useToast: () => ({ toast: vi.fn() }),
}));

vi.mock(
  "@/v2/pages-shared/traces/FeedbackScoresEditor/FeedbackScoresEditor",
  () => {
    const Stub = () => <div data-testid="feedback-scores-editor" />;
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

type StubCommentsSectionProps = {
  onSubmit: (text: string) => void;
  onDelete: (commentId: string) => void;
};

vi.mock("@/shared/UserComment/CommentsSection", () => ({
  default: ({ onSubmit, onDelete }: StubCommentsSectionProps) => (
    <div data-testid="comments-section">
      <button onClick={() => onSubmit("hello world")}>submit</button>
      <button onClick={() => onDelete("comment-1")}>delete</button>
    </div>
  ),
}));

describe("ThreadAnnotatePanel", () => {
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

  const THREAD_ID = "user-facing-thread-id";
  const THREAD_MODEL_ID = "00000000-0000-0000-0000-000000000abc";

  const renderPanel = () =>
    render(
      <ThreadAnnotatePanel
        threadId={THREAD_ID}
        threadModelId={THREAD_MODEL_ID}
        projectId="project-1"
        projectName="project-name"
        activeSection={DetailsActionSection.Annotate}
        setActiveSection={vi.fn()}
        feedbackScores={[]}
        comments={[]}
      />,
      { wrapper },
    );

  it("routes comment creation through thread_model_id, not the user-facing thread_id", () => {
    renderPanel();

    fireEvent.click(screen.getByRole("button", { name: "submit" }));

    expect(mockCreateMutate).toHaveBeenCalledTimes(1);
    expect(mockCreateMutate).toHaveBeenCalledWith({
      text: "hello world",
      threadId: THREAD_MODEL_ID,
      projectId: "project-1",
    });
    expect(mockCreateMutate).not.toHaveBeenCalledWith(
      expect.objectContaining({ threadId: THREAD_ID }),
    );
  });

  it("routes comment batch-delete through thread_model_id, not the user-facing thread_id", () => {
    renderPanel();

    fireEvent.click(screen.getByRole("button", { name: "delete" }));

    expect(mockBatchDeleteMutate).toHaveBeenCalledTimes(1);
    expect(mockBatchDeleteMutate).toHaveBeenCalledWith({
      ids: ["comment-1"],
      threadId: THREAD_MODEL_ID,
      projectId: "project-1",
    });
    expect(mockBatchDeleteMutate).not.toHaveBeenCalledWith(
      expect.objectContaining({ threadId: THREAD_ID }),
    );
  });
});
