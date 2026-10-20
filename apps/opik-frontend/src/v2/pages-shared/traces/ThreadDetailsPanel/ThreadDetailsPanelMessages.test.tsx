import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, fireEvent, within } from "@testing-library/react";
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

const renderPanel = (props: { traceId?: string } = {}) => {
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
              {...props}
            />
          </TooltipProvider>
        </PermissionsProvider>
      </QueryParamProvider>
    </QueryClientProvider>,
  );
};

const INTERNAL_TOOLTIP =
  "Internal call (e.g. title generation), hidden by default";

// One entry per rendered message, in order: its trace id and its internal badge (null when
// the message is not badged). Lets each test assert the whole Messages list at once.
const renderedMessages = () =>
  Array.from(document.querySelectorAll("[data-trace-message-id]")).map(
    (node) => ({
      id: node.getAttribute("data-trace-message-id"),
      badge:
        within(node as HTMLElement).queryByRole("note", {
          name: INTERNAL_TOOLTIP,
        })?.textContent ?? null,
    }),
  );

const turn = (id: string) => ({ id, badge: null });
const internal = (id: string) => ({ id, badge: "Internal" });

const hoverForTooltip = async (element: HTMLElement) => {
  fireEvent.pointerMove(element, { pointerType: "mouse" });
  return (await screen.findByRole("tooltip")).textContent;
};

describe("ThreadDetailsPanel messages", () => {
  beforeEach(() => {
    localStorage.clear();
    mocks.traces = [];
    mocks.thread = undefined;
  });

  it("hides internal traces by default, and the toggle shows them badged and hides them again", () => {
    mocks.traces = [
      makeTrace("trace-1"),
      makeTrace("trace-2", INTERNAL_METADATA),
      makeTrace("trace-3"),
    ];
    mocks.thread = makeThread(6);

    renderPanel();

    expect(renderedMessages()).toEqual([turn("trace-1"), turn("trace-3")]);
    expect(screen.getByText("4 messages")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Show hidden traces" }));

    expect(renderedMessages()).toEqual([
      turn("trace-1"),
      internal("trace-2"),
      turn("trace-3"),
    ]);
    expect(screen.getByText("6 messages")).toBeInTheDocument();

    fireEvent.click(
      screen.getByRole("button", { name: "Collapse hidden traces" }),
    );

    expect(renderedMessages()).toEqual([turn("trace-1"), turn("trace-3")]);
    expect(screen.getByText("4 messages")).toBeInTheDocument();
  });

  it("explains the internal badge in its tooltip", async () => {
    mocks.traces = [
      makeTrace("trace-1"),
      makeTrace("trace-2", INTERNAL_METADATA),
    ];
    mocks.thread = makeThread(4);

    renderPanel();
    fireEvent.click(screen.getByRole("button", { name: "Show hidden traces" }));

    expect(
      await hoverForTooltip(
        screen.getByRole("note", { name: INTERNAL_TOOLTIP }),
      ),
    ).toBe(INTERNAL_TOOLTIP);
  });

  it("explains the hidden traces in the toggle tooltip", async () => {
    mocks.traces = [
      makeTrace("trace-1"),
      makeTrace("trace-2", INTERNAL_METADATA),
    ];
    mocks.thread = makeThread(4);

    renderPanel();

    expect(
      await hoverForTooltip(
        screen.getByRole("button", { name: "Show hidden traces" }),
      ),
    ).toBe("Some traces are hidden. Click to show all traces.");
  });

  it("gives the full message count in the count tooltip while traces are hidden", async () => {
    mocks.traces = [
      makeTrace("trace-1"),
      makeTrace("trace-2", INTERNAL_METADATA),
    ];
    mocks.thread = makeThread(4);

    renderPanel();

    expect(await hoverForTooltip(screen.getByText("2 messages"))).toBe(
      "4 messages in the thread, hidden ones included",
    );
  });

  it("keeps the selected trace in Messages, badged, when it is internal", () => {
    mocks.traces = [
      makeTrace("trace-1"),
      makeTrace("trace-2", INTERNAL_METADATA),
      makeTrace("trace-3", INTERNAL_METADATA),
    ];
    mocks.thread = makeThread(6);

    renderPanel({ traceId: "trace-2" });

    expect(renderedMessages()).toEqual([turn("trace-1"), internal("trace-2")]);
    expect(screen.getByText("4 messages")).toBeInTheDocument();
  });

  it("renders every trace unbadged, with no toggle, when the thread has no internal traces", async () => {
    mocks.traces = [makeTrace("trace-1"), makeTrace("trace-2")];
    mocks.thread = makeThread(4);

    renderPanel();

    expect(renderedMessages()).toEqual([turn("trace-1"), turn("trace-2")]);
    expect(
      screen.queryByRole("button", { name: /hidden traces/ }),
    ).not.toBeInTheDocument();
    expect(await hoverForTooltip(screen.getByText("4 messages"))).toBe(
      "Number of messages in the thread",
    );
  });
});
