import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { QueryParamProvider } from "use-query-params";
import { WindowHistoryAdapter } from "use-query-params/adapters/window";
import { TooltipProvider } from "@/ui/tooltip";
import { PermissionsProvider } from "@/contexts/PermissionsContext";
import { DEFAULT_PERMISSIONS } from "@/types/permissions";
import { Thread, Trace } from "@/types/traces";
import { ThreadStatus } from "@/types/thread";
import ThreadDetailsPanel from "./ThreadDetailsPanel";

const THREAD_ID = "e5ff6303-d9af-45da-ab83-2f862e478ef0";

const makeTrace = (id: string, metadata: object = {}): Trace => ({
  id,
  name: id,
  input: { input: `question ${id}` },
  output: { output: `answer ${id}` },
  start_time: "2024-01-01T00:00:00Z",
  end_time: "2024-01-01T00:00:01Z",
  duration: 1000,
  created_at: "2024-01-01T00:00:00Z",
  last_updated_at: "2024-01-01T00:00:00Z",
  metadata,
  comments: [],
  tags: [],
  thread_id: THREAD_ID,
  project_id: "test-project-id",
});

const makeThread = (numberOfMessages: number): Thread => ({
  id: THREAD_ID,
  thread_model_id: "thread-model-id",
  project_id: "test-project-id",
  start_time: "2024-01-01T00:00:00Z",
  end_time: "2024-01-01T00:00:03Z",
  duration: 3000,
  first_message: {},
  last_message: {},
  number_of_messages: numberOfMessages,
  last_updated_at: "2024-01-01T00:00:03Z",
  created_by: "user",
  created_at: "2024-01-01T00:00:00Z",
  status: ThreadStatus.ACTIVE,
});

// Same flag the SDK sets on internal spans.
const INTERNAL_METADATA = { _opik: { is_internal: true } };

const mocks = vi.hoisted(() => ({
  traces: [] as Trace[],
  thread: undefined as Thread | undefined,
}));

vi.mock("clipboard-copy", () => ({ default: vi.fn() }));

vi.mock("@tanstack/react-router", () => ({
  useNavigate: () => vi.fn(),
}));

vi.mock("@/store/AppStore", () => ({
  default: vi.fn((selector) =>
    selector({
      activeWorkspaceName: "test-workspace",
      activeProjectId: "test-project-id",
    }),
  ),
  useActiveProjectId: () => "test-project-id",
  useLoggedInUserName: () => "test-user",
}));

vi.mock("@/api/traces/useThreadById", () => ({
  default: () => ({ data: mocks.thread, isPending: false }),
}));

vi.mock("@/api/traces/useTracesList", () => ({
  default: () => ({
    data: { content: mocks.traces, total: mocks.traces.length },
    isPending: false,
  }),
}));

vi.mock("@/api/traces/useThreadBatchDeleteMutation", () => ({
  default: () => ({ mutate: vi.fn() }),
}));

vi.mock("@/api/traces/useThreadFeedbackScoreDeleteMutation", () => ({
  default: () => ({ mutate: vi.fn() }),
}));

vi.mock("@/ui/use-toast", () => ({
  useToast: () => ({ toast: vi.fn() }),
  toast: vi.fn(),
}));

vi.mock("@/contexts/feature-toggles-provider", () => ({
  useIsFeatureEnabled: () => true,
}));

// "Add to" loads annotation queues over HTTP once the thread is loaded; not under test here.
vi.mock("@/v2/pages-shared/traces/AddToDropdown/AddToDropdown", () => ({
  default: () => null,
}));

vi.mock("@/hooks/useThreadMedia", () => ({
  useThreadMedia: () => ({ media: [] }),
}));

const renderPanel = () => {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });

  return render(
    <QueryClientProvider client={queryClient}>
      <QueryParamProvider adapter={WindowHistoryAdapter}>
        <PermissionsProvider value={DEFAULT_PERMISSIONS}>
          <TooltipProvider>
            <ThreadDetailsPanel
              projectId="test-project-id"
              projectName="test-project"
              threadId={THREAD_ID}
              setTraceId={vi.fn()}
              open
              onClose={vi.fn()}
            />
          </TooltipProvider>
        </PermissionsProvider>
      </QueryParamProvider>
    </QueryClientProvider>,
  );
};

const renderedMessageIds = (container: HTMLElement) =>
  Array.from(
    container.ownerDocument.querySelectorAll("[data-trace-message-id]"),
  ).map((node) => node.getAttribute("data-trace-message-id"));

describe("ThreadDetailsPanel messages", () => {
  beforeEach(() => {
    localStorage.clear();
    mocks.traces = [];
    mocks.thread = undefined;
  });

  it("hides internal traces by default and reveals them with the toggle", () => {
    mocks.traces = [
      makeTrace("trace-1"),
      makeTrace("trace-2", INTERNAL_METADATA),
      makeTrace("trace-3"),
    ];
    mocks.thread = makeThread(6);

    const { container } = renderPanel();

    expect(renderedMessageIds(container)).toEqual(["trace-1", "trace-3"]);
    expect(screen.getByText("4 messages")).toBeTruthy();

    fireEvent.click(screen.getByLabelText("Show hidden traces"));

    expect(renderedMessageIds(container)).toEqual([
      "trace-1",
      "trace-2",
      "trace-3",
    ]);
    expect(screen.getByText("6 messages")).toBeTruthy();

    fireEvent.click(screen.getByLabelText("Collapse hidden traces"));

    expect(renderedMessageIds(container)).toEqual(["trace-1", "trace-3"]);
  });

  it("renders every trace and no toggle when the thread has no internal traces", () => {
    mocks.traces = [makeTrace("trace-1"), makeTrace("trace-2")];
    mocks.thread = makeThread(4);

    const { container } = renderPanel();

    expect(renderedMessageIds(container)).toEqual(["trace-1", "trace-2"]);
    expect(screen.getByText("4 messages")).toBeTruthy();
    expect(screen.queryByLabelText("Show hidden traces")).toBeNull();
  });
});
