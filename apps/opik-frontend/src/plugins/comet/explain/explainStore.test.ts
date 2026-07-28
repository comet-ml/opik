import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";
import useExplainStore, {
  cellKey,
  ConsoleEmit,
  ExplainEntry,
  handleConsoleEvent,
} from "./explainStore";
import { ExplainKind, ExplainTarget } from "@/types/assistant-sidebar";
import { OpikEvent, trackEvent } from "@/lib/analytics/tracking";

vi.mock("@/lib/analytics/tracking", async (importOriginal) => {
  const actual =
    await importOriginal<typeof import("@/lib/analytics/tracking")>();
  return { ...actual, trackEvent: vi.fn() };
});

const target = (
  entityId: string,
  kind: ExplainKind = "trace.error",
): ExplainTarget => ({ kind, entityId, projectId: "p1", payload: {} });

// Key off the store's own formula (single source of truth) rather than
// re-implementing the cellKey template here.
const key = (entityId: string, kind: ExplainKind = "trace.error") =>
  cellKey(target(entityId, kind));

const settled = (
  explainId: string,
  text: string,
  startedAt: number,
): ExplainEntry => ({
  explainId,
  kind: "trace.error",
  phase: "done",
  text,
  startedAt,
});

const reset = (emit?: ConsoleEmit) =>
  useExplainStore.setState({
    entries: {},
    routes: {},
    capabilities: [],
    consoleBridgeVersion: null,
    ready: true,
    emit: emit ?? null,
  });

describe("explainStore", () => {
  // Fake timers so the watchdog (waking/timeout) is deterministic and no real
  // setTimeout leaks across tests.
  beforeEach(() => {
    vi.useFakeTimers();
    reset();
  });

  afterEach(() => {
    vi.clearAllTimers();
    vi.useRealTimers();
  });

  it("starts a stream and emits explain:run", () => {
    const emit = vi.fn();
    reset(emit);
    const t = target("e1");
    useExplainStore.getState().explain(t);

    expect(emit).toHaveBeenCalledWith(
      "explain:run",
      expect.objectContaining({ target: t }),
    );
    const entries = Object.values(useExplainStore.getState().entries);
    expect(entries).toHaveLength(1);
    expect(entries[0].phase).toBe("loading");
  });

  it("caps in-flight streams at MAX_IN_FLIGHT (3) and surfaces a retryable error past the cap", () => {
    const emit = vi.fn();
    reset(emit);
    const results = ["e1", "e2", "e3", "e4"].map((id) =>
      useExplainStore.getState().explain(target(id)),
    );

    const state = useExplainStore.getState();
    const loading = Object.values(state.entries).filter(
      (e) => e.phase === "loading",
    );
    expect(loading).toHaveLength(3);
    expect(results).toEqual([true, true, true, false]); // 4th throttled
    expect(emit).toHaveBeenCalledTimes(3); // throttled 4th dispatches nothing
    expect(Object.keys(state.routes)).toHaveLength(3); // error entry has no route
    // 4th is surfaced as a retryable error, not left stuck on "Thinking…".
    expect(state.entries[key("e4")].phase).toBe("error");
    expect(state.entries[key("e4")].error).toContain("Too many");
  });

  it("cancel() stops an in-flight stream and resets the cell", () => {
    const emit = vi.fn();
    reset(emit);
    const t = target("e1");
    useExplainStore.getState().explain(t);
    const { explainId } = Object.values(useExplainStore.getState().entries)[0];

    useExplainStore.getState().cancel(t);

    expect(emit).toHaveBeenCalledWith("explain:cancel", { explainId });
    expect(useExplainStore.getState().entries).toEqual({});
    expect(useExplainStore.getState().routes).toEqual({});
  });

  it("cancel() is a no-op on a settled cell", () => {
    const emit = vi.fn();
    reset(emit);
    useExplainStore.setState({
      entries: { [key("e1")]: settled("x", "answer", 0) },
    });

    useExplainStore.getState().cancel(target("e1"));

    expect(emit).not.toHaveBeenCalled();
    expect(useExplainStore.getState().entries[key("e1")]).toBeDefined();
  });

  it("continueChat mid-stream cancels, freezes partial text, and seeds chat", () => {
    const emit = vi.fn();
    reset(emit);
    const t = target("e1");
    useExplainStore.getState().explain(t);
    const { explainId } = Object.values(useExplainStore.getState().entries)[0];
    useExplainStore.getState().onChunk({ explainId, delta: "partial" });
    emit.mockClear();

    useExplainStore.getState().continueChat(t, "Why?");

    expect(emit).toHaveBeenNthCalledWith(1, "explain:cancel", { explainId });
    expect(emit).toHaveBeenNthCalledWith(2, "chat:continue", {
      question: "Why?",
      answer: "partial",
      target: t,
    });
    // Frozen to "done" so the popover-close cancel() won't re-cancel.
    expect(Object.values(useExplainStore.getState().entries)[0].phase).toBe(
      "done",
    );
    emit.mockClear();
    useExplainStore.getState().cancel(t);
    expect(emit).not.toHaveBeenCalled();
  });

  it("continueChat refuses a cell with no streamed text yet", () => {
    const emit = vi.fn();
    reset(emit);
    const t = target("e1");
    useExplainStore.getState().explain(t); // loading, text === ""
    emit.mockClear();

    useExplainStore.getState().continueChat(t, "Why?");

    expect(emit).not.toHaveBeenCalled();
  });

  it("continueChat on a completed cell seeds chat without cancelling", () => {
    const emit = vi.fn();
    reset(emit);
    useExplainStore.setState({
      entries: { [key("e1")]: settled("x", "answer", 0) },
    });

    useExplainStore.getState().continueChat(target("e1"), "Why?");

    expect(emit).toHaveBeenCalledTimes(1);
    expect(emit).toHaveBeenCalledWith("chat:continue", {
      question: "Why?",
      answer: "answer",
      target: target("e1"),
    });
  });

  it("evicts the oldest settled entry past the cache cap", () => {
    const entries: Record<string, ExplainEntry> = {};
    for (let i = 0; i < 200; i += 1) {
      entries[key(`old${i}`)] = settled(`id${i}`, "x", i);
    }
    reset(vi.fn());
    useExplainStore.setState({ entries });

    useExplainStore.getState().explain(target("fresh"));

    const state = useExplainStore.getState();
    expect(Object.keys(state.entries)).toHaveLength(200); // capped
    expect(state.entries[key("old0")]).toBeUndefined(); // oldest evicted
    expect(state.entries[key("fresh")]).toBeDefined();
  });

  it("re-runs a fresh stream when a cell is reopened after cancel", () => {
    const emit = vi.fn();
    reset(emit);
    const t = target("e1");
    useExplainStore.getState().explain(t);
    const first = Object.values(useExplainStore.getState().entries)[0]
      .explainId;

    useExplainStore.getState().cancel(t);
    useExplainStore.getState().explain(t); // reopen

    const entries = Object.values(useExplainStore.getState().entries);
    expect(entries).toHaveLength(1);
    expect(entries[0].phase).toBe("loading");
    expect(entries[0].explainId).not.toBe(first); // genuinely fresh, not stuck
    expect(emit.mock.calls.filter((c) => c[0] === "explain:run")).toHaveLength(
      2,
    );
  });

  it("continueChat refuses an errored cell even when it has text", () => {
    const emit = vi.fn();
    reset(emit);
    useExplainStore.setState({
      entries: {
        [key("e1")]: {
          explainId: "x",
          kind: "trace.error",
          phase: "error",
          text: "partial before failure",
          startedAt: 0,
          error: "boom",
        },
      },
    });

    useExplainStore.getState().continueChat(target("e1"), "Why?");

    expect(emit).not.toHaveBeenCalled();
  });

  it("cancel() with no emitter still resets the cell and does not throw", () => {
    reset(); // emit === null (pod disconnected mid-stream)
    const t = target("e1");
    useExplainStore.getState().explain(t); // entry created, no emit
    expect(Object.keys(useExplainStore.getState().entries)).toHaveLength(1);

    expect(() => useExplainStore.getState().cancel(t)).not.toThrow();
    expect(useExplainStore.getState().entries).toEqual({});
    expect(useExplainStore.getState().routes).toEqual({});
  });

  it("handleConsoleEvent routes explain:chunk/done to the streaming cell", () => {
    const emit = vi.fn();
    reset(emit);
    useExplainStore.getState().explain(target("e1"));
    const { explainId } = Object.values(useExplainStore.getState().entries)[0];

    expect(
      handleConsoleEvent("explain:chunk", { explainId, delta: "ab" }),
    ).toBe(true);
    expect(useExplainStore.getState().entries[key("e1")].text).toBe("ab");

    expect(handleConsoleEvent("explain:done", { explainId })).toBe(true);
    expect(useExplainStore.getState().entries[key("e1")].phase).toBe("done");
  });

  it("handleConsoleEvent routes explain:error with its message", () => {
    const emit = vi.fn();
    reset(emit);
    useExplainStore.getState().explain(target("e1"));
    const { explainId } = Object.values(useExplainStore.getState().entries)[0];

    expect(
      handleConsoleEvent("explain:error", { explainId, message: "boom" }),
    ).toBe(true);
    const entry = useExplainStore.getState().entries[key("e1")];
    expect(entry.phase).toBe("error");
    expect(entry.error).toBe("boom");
  });

  it("handleConsoleEvent applies console:ready and ignores unknown events", () => {
    reset(vi.fn());
    expect(
      handleConsoleEvent("console:ready", {
        bridgeVersion: 2,
        capabilities: ["explain"],
      }),
    ).toBe(true);
    expect(useExplainStore.getState().capabilities).toEqual(["explain"]);
    expect(useExplainStore.getState().consoleBridgeVersion).toBe(2);

    // A non-explain event falls through so the bridge handles it itself.
    expect(handleConsoleEvent("navigate", { path: "/x" })).toBe(false);
  });

  it("retry() clears an errored cell and dispatches a fresh stream", () => {
    const emit = vi.fn();
    reset(emit);
    useExplainStore.setState({
      entries: {
        [key("e1")]: {
          explainId: "old",
          kind: "trace.error",
          phase: "error",
          text: "",
          error: "boom",
          startedAt: 0,
        },
      },
    });

    useExplainStore.getState().retry(target("e1"));

    const entry = useExplainStore.getState().entries[key("e1")];
    expect(entry.phase).toBe("loading");
    expect(entry.explainId).not.toBe("old");
    expect(emit).toHaveBeenCalledWith(
      "explain:run",
      expect.objectContaining({ target: target("e1") }),
    );
  });

  it("explain() reuses an in-flight or done cell without re-dispatching", () => {
    const emit = vi.fn();
    reset(emit);
    const t = target("e1");
    expect(useExplainStore.getState().explain(t)).toBe(true); // fresh dispatch
    expect(useExplainStore.getState().explain(t)).toBe(true); // loading reuse
    const { explainId } = Object.values(useExplainStore.getState().entries)[0];
    useExplainStore.getState().onDone({ explainId });
    expect(useExplainStore.getState().explain(t)).toBe(true); // done reuse

    expect(emit.mock.calls.filter((c) => c[0] === "explain:run")).toHaveLength(
      1,
    );
  });

  it("clearEmit wipes state only for the current emitter", () => {
    const emit = vi.fn();
    useExplainStore.setState({
      emit,
      capabilities: ["explain"],
      consoleBridgeVersion: 2,
      ready: true,
    });

    // A stale (different) emitter must not drop the live channel.
    useExplainStore.getState().clearEmit(vi.fn());
    expect(useExplainStore.getState().emit).toBe(emit);
    expect(useExplainStore.getState().capabilities).toEqual(["explain"]);

    // The current emitter wipes the handshake-derived gating state.
    useExplainStore.getState().clearEmit(emit);
    const s = useExplainStore.getState();
    expect(s.emit).toBeNull();
    expect(s.capabilities).toEqual([]);
    expect(s.consoleBridgeVersion).toBeNull();
    expect(s.ready).toBe(false);
  });

  it("scopes cached entries by projectId", () => {
    reset(vi.fn());
    const a: ExplainTarget = {
      kind: "trace.error",
      entityId: "same",
      projectId: "pA",
      payload: {},
    };
    const b: ExplainTarget = { ...a, projectId: "pB" };

    useExplainStore.getState().explain(a);
    useExplainStore.getState().explain(b);

    expect(Object.keys(useExplainStore.getState().entries)).toHaveLength(2);
  });

  it("ignores chunks for a retired or unknown route", () => {
    const emit = vi.fn();
    reset(emit);
    useExplainStore.getState().explain(target("e1"));
    const { explainId } = Object.values(useExplainStore.getState().entries)[0];
    useExplainStore.getState().onChunk({ explainId, delta: "hi" });
    useExplainStore.getState().onDone({ explainId }); // retires the route

    useExplainStore.getState().onChunk({ explainId, delta: " late" });
    expect(useExplainStore.getState().entries[key("e1")].text).toBe("hi");
    expect(() =>
      useExplainStore.getState().onChunk({ explainId: "nope", delta: "x" }),
    ).not.toThrow();
  });

  it("eviction never drops in-flight (loading) entries", () => {
    const entries: Record<string, ExplainEntry> = {};
    for (let i = 0; i < 199; i += 1) {
      entries[key(`old${i}`)] = settled(`id${i}`, "x", i);
    }
    reset(vi.fn());
    useExplainStore.setState({ entries });

    useExplainStore.getState().explain(target("live")); // 200th entry, loading
    useExplainStore.getState().explain(target("fresh2")); // forces an eviction

    const live = useExplainStore.getState().entries[key("live")];
    expect(live).toBeDefined();
    expect(live.phase).toBe("loading"); // a settled entry was evicted, not this
  });

  it("nudges a stalled stream to 'waking' when no chunk arrives", () => {
    reset(vi.fn());
    useExplainStore.getState().explain(target("e1"));
    expect(useExplainStore.getState().entries[key("e1")].phase).toBe("loading");

    vi.advanceTimersByTime(10_000);

    expect(useExplainStore.getState().entries[key("e1")].phase).toBe("waking");
  });

  it("times out a stalled stream into a retryable error and cancels it", () => {
    const emit = vi.fn();
    reset(emit);
    useExplainStore.getState().explain(target("e1"));
    const { explainId } = Object.values(useExplainStore.getState().entries)[0];

    vi.advanceTimersByTime(30_000);

    const entry = useExplainStore.getState().entries[key("e1")];
    expect(entry.phase).toBe("error");
    expect(entry.code).toBe("timeout");
    expect(trackEvent).toHaveBeenCalledWith(OpikEvent.EXPLAIN_ERRORED, {
      kind: "trace.error",
      code: "timeout",
      message: expect.stringMatching(/too long/i),
    });
    expect(emit).toHaveBeenCalledWith("explain:cancel", { explainId });
    // Route retired, so a late chunk that finally arrives is ignored.
    expect(useExplainStore.getState().routes[explainId]).toBeUndefined();
  });

  it("a streamed chunk retires the watchdog (no waking, no timeout)", () => {
    const emit = vi.fn();
    reset(emit);
    useExplainStore.getState().explain(target("e1"));
    const { explainId } = Object.values(useExplainStore.getState().entries)[0];
    useExplainStore.getState().onChunk({ explainId, delta: "hi" });

    vi.advanceTimersByTime(60_000);

    const entry = useExplainStore.getState().entries[key("e1")];
    expect(entry.phase).toBe("loading");
    expect(entry.text).toBe("hi");
  });

  it("fails in-flight cells when the pod goes unready (setReady false)", () => {
    reset(vi.fn());
    useExplainStore.getState().explain(target("e1"));

    useExplainStore.getState().setReady(false);

    const entry = useExplainStore.getState().entries[key("e1")];
    expect(entry.phase).toBe("error");
    expect(entry.code).toBe("unavailable");
  });

  it("fails in-flight cells when the bridge tears down (clearEmit)", () => {
    const emit = vi.fn();
    reset(emit);
    useExplainStore.getState().explain(target("e1"));

    useExplainStore.getState().clearEmit(emit);

    const entry = useExplainStore.getState().entries[key("e1")];
    expect(entry.phase).toBe("error");
    expect(entry.code).toBe("unavailable");
    expect(useExplainStore.getState().emit).toBeNull();
  });

  it("maps a structured explain:error code to contextual copy", () => {
    reset(vi.fn());
    useExplainStore.getState().explain(target("e1"));
    const { explainId } = Object.values(useExplainStore.getState().entries)[0];

    handleConsoleEvent("explain:error", {
      explainId,
      message: "raw upstream text",
      code: "unavailable",
    });

    const entry = useExplainStore.getState().entries[key("e1")];
    expect(entry.phase).toBe("error");
    expect(entry.code).toBe("unavailable");
    expect(trackEvent).toHaveBeenCalledWith(OpikEvent.EXPLAIN_ERRORED, {
      kind: "trace.error",
      code: "unavailable",
      message: expect.stringMatching(/unavailable/i),
    });
    expect(entry.error).not.toBe("raw upstream text"); // friendly copy wins
    expect(entry.error).toMatch(/unavailable/i);
  });

  it("recovers an errored cell when a retry streams after the error (error → chunk → done)", () => {
    // Repro of the sleeping-pod bug: the console emits explain:error on a
    // transient 503, then its retry succeeds and streams chunks under the SAME
    // explainId. The cell must resurrect and show the answer, not stay stuck.
    const emit = vi.fn();
    reset(emit);
    useExplainStore.getState().explain(target("e1"));
    const { explainId } = Object.values(useExplainStore.getState().entries)[0];

    handleConsoleEvent("explain:error", { explainId, message: "boom" });
    expect(useExplainStore.getState().entries[key("e1")].phase).toBe("error");
    // The route survives the (recoverable) console error so chunks can route.
    expect(useExplainStore.getState().routes[explainId]).toBe(key("e1"));

    handleConsoleEvent("explain:chunk", { explainId, delta: "hello " });
    handleConsoleEvent("explain:chunk", { explainId, delta: "world" });
    handleConsoleEvent("explain:done", { explainId });

    const entry = useExplainStore.getState().entries[key("e1")];
    expect(entry.phase).toBe("done");
    expect(entry.text).toBe("hello world"); // fresh retry stream, not appended
    expect(entry.error).toBeUndefined(); // stale error cleared on recovery
    // done is terminal → route retired.
    expect(useExplainStore.getState().routes[explainId]).toBeUndefined();
  });

  it("keeps a terminal error (error → done with no chunks) as an error, not a blank done", () => {
    const emit = vi.fn();
    reset(emit);
    useExplainStore.getState().explain(target("e1"));
    const { explainId } = Object.values(useExplainStore.getState().entries)[0];

    handleConsoleEvent("explain:error", { explainId, message: "boom" });
    handleConsoleEvent("explain:done", { explainId }); // never recovered

    const entry = useExplainStore.getState().entries[key("e1")];
    expect(entry.phase).toBe("error"); // not blanked into an empty "done"
    expect(entry.error).toBe("boom");
    expect(useExplainStore.getState().routes[explainId]).toBeUndefined();
  });

  it("re-explaining an errored cell drops the stale route so a late event from the abandoned stream cannot corrupt the new one", () => {
    // onError keeps the errored stream's route (for a same-id console retry).
    // Reopening the popover calls explain() — not retry() — starting a NEW
    // explainId. If the old route lingers, a late chunk/done/error for the
    // abandoned stream aliases into the live cell and corrupts the answer.
    const emit = vi.fn();
    reset(emit);
    useExplainStore.getState().explain(target("e1"));
    const oldId = Object.values(useExplainStore.getState().entries)[0]
      .explainId;
    handleConsoleEvent("explain:error", { explainId: oldId, message: "boom" });
    expect(useExplainStore.getState().routes[oldId]).toBe(key("e1"));

    // Reopen → fresh stream under a new explainId; the stale route is dropped.
    useExplainStore.getState().explain(target("e1"));
    const newId = Object.values(useExplainStore.getState().entries)[0]
      .explainId;
    expect(newId).not.toBe(oldId);
    expect(useExplainStore.getState().routes[oldId]).toBeUndefined();
    expect(useExplainStore.getState().routes[newId]).toBe(key("e1"));

    // A late event for the abandoned stream is now a no-op.
    handleConsoleEvent("explain:chunk", { explainId: oldId, delta: "STALE" });
    handleConsoleEvent("explain:done", { explainId: oldId });
    expect(useExplainStore.getState().entries[key("e1")].text).toBe("");

    // The live stream still streams cleanly into the cell.
    handleConsoleEvent("explain:chunk", { explainId: newId, delta: "real" });
    handleConsoleEvent("explain:done", { explainId: newId });
    const entry = useExplainStore.getState().entries[key("e1")];
    expect(entry.phase).toBe("done");
    expect(entry.text).toBe("real");
  });

  it("does not resolve a prototype-key error code to a Function (renders a safe string)", () => {
    // `code` is free-form off the bridge; a bare ERROR_COPY[code] lookup would
    // return Object.prototype.constructor (a Function) for code:"constructor",
    // crashing the popover when rendered as a React child.
    const emit = vi.fn();
    reset(emit);
    useExplainStore.getState().explain(target("e1"));
    const { explainId } = Object.values(useExplainStore.getState().entries)[0];

    handleConsoleEvent("explain:error", {
      explainId,
      code: "constructor",
      message: "boom",
    });

    const entry = useExplainStore.getState().entries[key("e1")];
    expect(entry.phase).toBe("error");
    expect(typeof entry.error).toBe("string");
    expect(entry.error).toBe("boom"); // falls through to the raw message
    // The raw upstream text also reaches telemetry — the diagnostic detail
    // for codes with no friendly copy.
    expect(trackEvent).toHaveBeenCalledWith(OpikEvent.EXPLAIN_ERRORED, {
      kind: "trace.error",
      code: "constructor",
      message: "boom",
    });
  });
});
