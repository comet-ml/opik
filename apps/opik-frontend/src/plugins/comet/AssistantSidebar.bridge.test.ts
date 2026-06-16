import { describe, it, expect, vi } from "vitest";
import {
  createHostListeners,
  emitHostEvent,
} from "@/plugins/comet/AssistantSidebar";
import { ExplainTarget } from "@/types/assistant-sidebar";

const target: ExplainTarget = {
  kind: "trace.error",
  entityId: "t1",
  projectId: "p1",
  payload: {},
};

// VER-4 runtime smoke-test. The compile-time contract assertion
// (assistant-sidebar.contract.ts) guards that the event KEYS exist in both
// maps; this confirms the host actually DISPATCHES the three new host->shell
// events to subscribed listeners at runtime — i.e. createHostListeners()
// allocates the Sets and emitHostEvent() iterates the right one.
describe("bridge host-event dispatch (VER-4 runtime smoke)", () => {
  it("delivers explain:run / explain:cancel / chat:continue to subscribers", () => {
    const listenersRef = { current: createHostListeners() };

    const onRun = vi.fn();
    const onCancel = vi.fn();
    const onContinue = vi.fn();
    listenersRef.current["explain:run"].add(onRun);
    listenersRef.current["explain:cancel"].add(onCancel);
    listenersRef.current["chat:continue"].add(onContinue);

    emitHostEvent(listenersRef, "explain:run", { explainId: "e1", target });
    emitHostEvent(listenersRef, "explain:cancel", { explainId: "e1" });
    emitHostEvent(listenersRef, "chat:continue", {
      question: "q",
      answer: "a",
      target,
    });

    expect(onRun).toHaveBeenCalledWith({ explainId: "e1", target });
    expect(onCancel).toHaveBeenCalledWith({ explainId: "e1" });
    expect(onContinue).toHaveBeenCalledWith({
      question: "q",
      answer: "a",
      target,
    });
  });

  it("does not cross-deliver between event channels", () => {
    const listenersRef = { current: createHostListeners() };
    const onCancel = vi.fn();
    listenersRef.current["explain:cancel"].add(onCancel);

    emitHostEvent(listenersRef, "explain:run", { explainId: "e2", target });

    expect(onCancel).not.toHaveBeenCalled();
  });
});
