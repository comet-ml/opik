import { describe, it, expect, beforeEach, vi } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import useExplainStore, { cellKey, ExplainEntry } from "./explainStore";
import ExplainPopover from "./ExplainPopover";
import { ExplainTarget } from "@/types/assistant-sidebar";

const target: ExplainTarget = {
  kind: "trace.error",
  entityId: "e1",
  projectId: "p1",
  payload: { exception_type: "ValueError" },
};
const key = cellKey(target);

const entry = (over: Partial<ExplainEntry>): ExplainEntry => ({
  explainId: "x",
  kind: "trace.error",
  phase: "done",
  text: "",
  startedAt: 0,
  ...over,
});

const seed = (e: ExplainEntry | null, emit = vi.fn()) =>
  useExplainStore.setState({
    entries: e ? { [key]: e } : {},
    routes: {},
    ready: true,
    capabilities: ["explain"],
    emit,
  });

const renderPopover = (onContinue = vi.fn()) =>
  render(<ExplainPopover target={target} onContinue={onContinue} />);

describe("ExplainPopover", () => {
  beforeEach(() => seed(null));

  it("shows the Thinking pulse when there is no entry yet", () => {
    seed(null);
    renderPopover();
    expect(screen.getByText("Thinking...")).toBeInTheDocument();
  });

  it("shows the 'waking up' hint for a waking entry", () => {
    seed(entry({ phase: "waking", text: "" }));
    renderPopover();
    expect(screen.getByText("Ollie is waking up…")).toBeInTheDocument();
  });

  it("shows the error message and a Retry button on error", () => {
    seed(entry({ phase: "error", error: "boom" }));
    renderPopover();
    expect(screen.getByText("boom")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Retry" })).toBeInTheDocument();
  });

  it("shows 'No explanation available.' for an empty done entry", () => {
    seed(entry({ phase: "done", text: "" }));
    renderPopover();
    expect(screen.getByText("No explanation available.")).toBeInTheDocument();
  });

  it("renders streamed text + Continue link while still loading", () => {
    seed(entry({ phase: "loading", text: "partial answer" }));
    renderPopover();
    expect(screen.getByText("partial answer")).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: /Continue conversation/ }),
    ).toBeInTheDocument();
  });

  it("Continue seeds the chat with the answer and calls onContinue", () => {
    const emit = vi.fn();
    seed(entry({ phase: "done", text: "answer" }), emit);
    const onContinue = vi.fn();
    renderPopover(onContinue);

    fireEvent.click(
      screen.getByRole("button", { name: /Continue conversation/ }),
    );

    expect(emit).toHaveBeenCalledWith(
      "chat:continue",
      expect.objectContaining({ answer: "answer", target }),
    );
    expect(onContinue).toHaveBeenCalledTimes(1);
  });

  it("Retry clears the cell and dispatches a fresh stream", () => {
    const emit = vi.fn();
    seed(entry({ phase: "error", error: "boom" }), emit);
    renderPopover();

    fireEvent.click(screen.getByRole("button", { name: "Retry" }));

    expect(emit).toHaveBeenCalledWith(
      "explain:run",
      expect.objectContaining({ target }),
    );
  });
});
