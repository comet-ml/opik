import { describe, it, expect, beforeEach, vi } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import useExplainStore from "./explainStore";
import { ExplainTarget } from "@/types/assistant-sidebar";
import { OpikEvent, trackEvent } from "@/lib/analytics/tracking";
import ExplainButton from "./ExplainButton";

vi.mock("@/lib/analytics/tracking", async (importOriginal) => {
  const actual =
    await importOriginal<typeof import("@/lib/analytics/tracking")>();
  return { ...actual, trackEvent: vi.fn() };
});

const target: ExplainTarget = {
  kind: "trace.error",
  entityId: "e1",
  projectId: "p1",
  payload: { exception_type: "ValueError" },
};

// Drive useCanExplain to "on": bridge connected, pod ready, capability present,
// compatible bridge version.
const enable = (emit = vi.fn()) =>
  useExplainStore.setState({
    ready: true,
    capabilities: ["explain"],
    consoleBridgeVersion: 2,
    emit,
    entries: {},
    routes: {},
  });

describe("ExplainButton", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    enable();
  });

  it("renders nothing when the gate is closed", () => {
    enable();
    useExplainStore.setState({ ready: false });
    const { container } = render(<ExplainButton target={target} />);
    expect(container).toBeEmptyDOMElement();
  });

  it("renders the trigger when explain is available", () => {
    render(<ExplainButton target={target} />);
    expect(
      screen.getByRole("button", { name: "Explain error" }),
    ).toBeInTheDocument();
  });

  it("dispatches a stream and tracks the click on open", () => {
    const emit = vi.fn();
    enable(emit);
    render(<ExplainButton target={target} />);

    fireEvent.click(screen.getByRole("button", { name: "Explain error" }));

    expect(emit).toHaveBeenCalledWith(
      "explain:run",
      expect.objectContaining({ target }),
    );
    expect(trackEvent).toHaveBeenCalledWith(OpikEvent.EXPLAIN_CLICKED, {
      kind: "trace.error",
    });
  });

  it("cancels the in-flight stream on close", () => {
    const emit = vi.fn();
    enable(emit);
    render(<ExplainButton target={target} />);
    const trigger = screen.getByRole("button", { name: "Explain error" });

    fireEvent.click(trigger); // open → dispatch
    fireEvent.click(trigger); // close → cancel

    expect(emit).toHaveBeenCalledWith(
      "explain:cancel",
      expect.objectContaining({}),
    );
  });
});
