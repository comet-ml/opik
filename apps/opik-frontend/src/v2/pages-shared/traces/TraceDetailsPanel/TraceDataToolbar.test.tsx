import { describe, it, expect, vi, beforeEach } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import { TooltipProvider } from "@/ui/tooltip";
import { PermissionsProvider } from "@/contexts/PermissionsContext";
import { DEFAULT_PERMISSIONS } from "@/types/permissions";
import { Span, Trace } from "@/types/traces";
import { TraceDataToolbar } from "./TraceDetailsToolbar";

vi.mock("clipboard-copy", () => ({ default: vi.fn() }));

vi.mock("@/v2/pages-shared/traces/AddToDropdown/AddToDropdown", () => {
  const Stub = () => <div data-testid="add-to-dropdown" />;
  Stub.displayName = "AddToDropdownStub";
  return { default: Stub };
});

vi.mock("@/contexts/feature-toggles-provider", () => ({
  useIsFeatureEnabled: () => true,
}));

const loadSpan = vi.fn();
vi.mock("@/v2/pages-shared/playground/useLoadSpanIntoPlayground", () => ({
  default: () => ({
    loadSpan,
    isPlaygroundEmpty: true,
    isPending: false,
  }),
}));

const TRACE_ID = "019ce65c-e695-7f93-b75b-68a4a9141f09";
const SPAN_ID = "019ce65c-ec62-7753-acf0-4935ce3333da";

const trace = { id: TRACE_ID, name: "handle_query" } as Trace;
const span = {
  id: SPAN_ID,
  name: "chat_completion_create",
  type: "llm",
  trace_id: TRACE_ID,
  parent_span_id: "",
} as unknown as Span;
const llmSpanWithMessages = {
  ...span,
  input: { messages: [{ role: "user", content: "Hi" }] },
} as Span;

const renderToolbar = (props = {}, permissions = DEFAULT_PERMISSIONS) =>
  render(
    <PermissionsProvider value={permissions}>
      <TooltipProvider delayDuration={0}>
        <TraceDataToolbar
          dataToView={span}
          setActiveSection={vi.fn()}
          {...props}
        />
      </TooltipProvider>
    </PermissionsProvider>,
  );

describe("TraceDataToolbar header", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("renders the span name when a span is selected", () => {
    renderToolbar({ dataToView: span });

    expect(screen.getByText("chat_completion_create")).toBeTruthy();
  });

  it("renders the trace name when a trace is selected", () => {
    renderToolbar({ dataToView: trace });

    expect(screen.getByText("handle_query")).toBeTruthy();
  });

  it("renders the name without a Span:/Trace: prefix", () => {
    renderToolbar({ dataToView: span });

    expect(screen.queryByText(/Inspect:/)).toBeNull();
    expect(screen.queryByText(/^Span: /)).toBeNull();
  });

  it("falls back to the entity type when the name is missing", () => {
    renderToolbar({ dataToView: { ...span, name: "" } });

    expect(screen.getByText("Span")).toBeTruthy();
  });

  // The URL is page-scoped and the panel header already owns a link action, so
  // this nested toolbar offers the id only.
  it("renders a span copy-id action without a link action", () => {
    renderToolbar({ dataToView: span });

    expect(screen.getByLabelText("Copy span ID")).toBeTruthy();
    expect(screen.queryByLabelText("Copy span link")).toBeNull();
  });

  it("renders a trace copy-id action without a link action", () => {
    renderToolbar({ dataToView: trace });

    expect(screen.getByLabelText("Copy trace ID")).toBeTruthy();
    expect(screen.queryByLabelText("Copy trace link")).toBeNull();
  });

  it("keeps copy actions visible when annotate actions are hidden", () => {
    renderToolbar({ dataToView: span, hideAnnotateActions: true });

    expect(screen.getByLabelText("Copy span ID")).toBeTruthy();
    expect(screen.queryByTestId("add-to-dropdown")).toBeNull();
  });

  // The title is wrapped so hovering reveals the full id. Radix renders the
  // content through a portal that never materializes under happy-dom, so this
  // asserts the title is a tooltip trigger at all; the text it reveals is
  // verified manually.
  it("makes the title a tooltip trigger", () => {
    renderToolbar({ dataToView: span });

    expect(
      screen.getByText("chat_completion_create").getAttribute("data-state"),
    ).toBe("closed");
  });

  it("renders no title or copy actions while loading", () => {
    renderToolbar({ dataToView: undefined, isLoading: true });

    expect(screen.queryByLabelText(/^Copy /)).toBeNull();
    expect(screen.queryByText("chat_completion_create")).toBeNull();
  });
});

describe("TraceDataToolbar open in Playground", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("loads an LLM span with input messages into the Playground", () => {
    renderToolbar({ dataToView: llmSpanWithMessages });

    fireEvent.click(screen.getByLabelText("Open in Playground"));

    expect(loadSpan).toHaveBeenCalledWith(llmSpanWithMessages);
  });

  it("is hidden for a trace", () => {
    renderToolbar({
      dataToView: { ...trace, input: llmSpanWithMessages.input },
    });

    expect(screen.queryByLabelText("Open in Playground")).toBeNull();
  });

  it("is hidden for a span that isn't an LLM call", () => {
    renderToolbar({ dataToView: { ...llmSpanWithMessages, type: "general" } });

    expect(screen.queryByLabelText("Open in Playground")).toBeNull();
  });

  it("is hidden when the span input isn't messages", () => {
    renderToolbar({ dataToView: { ...span, input: { query: "Hi" } } });

    expect(screen.queryByLabelText("Open in Playground")).toBeNull();
  });

  it("is hidden when the span input can't be mapped", () => {
    renderToolbar({
      dataToView: {
        ...span,
        input: { messages: [{ role: "user", content: [null] }] },
      },
    });

    expect(screen.getByText("chat_completion_create")).toBeTruthy();
    expect(screen.queryByLabelText("Open in Playground")).toBeNull();
  });

  it("is hidden without Playground access", () => {
    renderToolbar(
      { dataToView: llmSpanWithMessages },
      {
        ...DEFAULT_PERMISSIONS,
        permissions: {
          ...DEFAULT_PERMISSIONS.permissions,
          canUsePlayground: false,
        },
      },
    );

    expect(screen.queryByLabelText("Open in Playground")).toBeNull();
  });
});
