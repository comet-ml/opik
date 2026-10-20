import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, fireEvent, within } from "@testing-library/react";
import { QueryParamProvider } from "use-query-params";
import { WindowHistoryAdapter } from "use-query-params/adapters/window";
import { TooltipProvider } from "@/ui/tooltip";
import { Thread, Trace } from "@/types/traces";
import { ThreadStatus } from "@/types/thread";
import ThreadDataViewer from "./ThreadDataViewer";

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

const THREAD: Thread = {
  id: THREAD_ID,
  thread_model_id: "thread-model-id",
  project_id: "test-project-id",
  start_time: "2024-01-01T00:00:00Z",
  end_time: "2024-01-01T00:00:03Z",
  duration: 3000,
  first_message: {},
  last_message: {},
  number_of_messages: 6,
  last_updated_at: "2024-01-01T00:00:03Z",
  created_by: "user",
  created_at: "2024-01-01T00:00:00Z",
  status: ThreadStatus.ACTIVE,
};

// Same flag the SDK sets on internal spans.
const INTERNAL_METADATA = { _opik: { is_internal: true } };

const INTERNAL_TOOLTIP =
  "Internal call (e.g. title generation), hidden by default";

const mocks = vi.hoisted(() => ({
  traces: [] as Trace[],
}));

vi.mock("../SMEFlowContext", () => ({
  useSMEFlow: () => ({
    currentItem: THREAD,
    nextDefaultItem: undefined,
    annotationQueue: { project_name: "test-project" },
  }),
}));

vi.mock("@/api/traces/useTracesList", () => ({
  default: () => ({
    data: { content: mocks.traces, total: mocks.traces.length },
    isFetching: false,
  }),
}));

// The side panels open over the annotation view on demand; not under test here.
vi.mock("@/v2/pages-shared/traces/TraceDetailsPanel/TraceDetailsPanel", () => ({
  default: () => null,
}));

vi.mock(
  "@/v2/pages-shared/traces/ThreadDetailsPanel/ThreadDetailsPanel",
  () => ({
    default: () => null,
  }),
);

vi.mock("@/v2/pages-shared/traces/TraceMessages/LikeFeedback", () => ({
  default: () => null,
}));

const renderViewer = () =>
  render(
    <QueryParamProvider adapter={WindowHistoryAdapter}>
      <TooltipProvider>
        <ThreadDataViewer.Header />
        <ThreadDataViewer.Content />
      </TooltipProvider>
    </QueryParamProvider>,
  );

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

describe("ThreadDataViewer (annotation queue thread view)", () => {
  beforeEach(() => {
    localStorage.clear();
    window.history.replaceState(null, "", "/");
    mocks.traces = [];
  });

  it("hides internal traces by default, and the toggle shows them badged and hides them again", () => {
    mocks.traces = [
      makeTrace("trace-1"),
      makeTrace("trace-2", INTERNAL_METADATA),
      makeTrace("trace-3"),
    ];

    renderViewer();

    expect(renderedMessages()).toEqual([turn("trace-1"), turn("trace-3")]);

    fireEvent.click(
      screen.getByRole("button", { name: "Show internal calls (1)" }),
    );

    expect(renderedMessages()).toEqual([
      turn("trace-1"),
      internal("trace-2"),
      turn("trace-3"),
    ]);

    fireEvent.click(
      screen.getByRole("button", { name: "Hide internal calls (1)" }),
    );

    expect(renderedMessages()).toEqual([turn("trace-1"), turn("trace-3")]);
  });

  it("renders every trace unbadged, with no toggle, when the thread has no internal traces", () => {
    mocks.traces = [makeTrace("trace-1"), makeTrace("trace-2")];

    renderViewer();

    expect(renderedMessages()).toEqual([turn("trace-1"), turn("trace-2")]);
    expect(
      screen.queryByRole("button", { name: /internal calls/ }),
    ).not.toBeInTheDocument();
  });

  it("keeps the trace open in the trace panel listed, badged, when it is internal", () => {
    mocks.traces = [
      makeTrace("trace-1"),
      makeTrace("trace-2", INTERNAL_METADATA),
      makeTrace("trace-3", INTERNAL_METADATA),
    ];
    window.history.replaceState(null, "", "/?trace=trace-2");

    renderViewer();

    expect(renderedMessages()).toEqual([turn("trace-1"), internal("trace-2")]);
    expect(
      screen.getByRole("button", { name: "Show internal calls (1)" }),
    ).toBeInTheDocument();
  });

  it("offers no control when the only internal trace is the one open in the trace panel", () => {
    mocks.traces = [
      makeTrace("trace-1"),
      makeTrace("trace-2", INTERNAL_METADATA),
    ];
    window.history.replaceState(null, "", "/?trace=trace-2");

    renderViewer();

    expect(renderedMessages()).toEqual([turn("trace-1"), internal("trace-2")]);
    expect(
      screen.queryByRole("button", { name: /internal calls/ }),
    ).not.toBeInTheDocument();
  });
});
