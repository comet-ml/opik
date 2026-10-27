import { afterEach, describe, expect, it, vi } from "vitest";
import type { MutableRefObject } from "react";
import {
  createBridge,
  createHostListeners,
  dropStalePending,
  emitHostEvent,
  PENDING_REQUEST_TTL_MS,
  type BridgeRefs,
  type HostListeners,
} from "./assistantBridge";
import useExplainStore from "./explain/explainStore";
import { registerArtifactHandler } from "@/lib/ollieArtifacts";

// Minimal refs: only `listeners` matters for these tests; the rest are unused
// no-op handles the bridge reads through.
const ref = <T>(current: T): MutableRefObject<T> => ({ current });

const makeRefs = (listeners: HostListeners): BridgeRefs => ({
  navigate: ref(() => {}),
  onWidthChange: ref(() => {}),
  onNotification: ref(() => {}),
  onRequestVisibility: ref(() => {}),
  onRequestPair: ref(() => {}),
  context: ref({} as BridgeRefs["context"]["current"]),
  listeners: ref(listeners),
  lastRunnerState: ref(null),
});

describe("assistantBridge — single-subscriber invariant", () => {
  // Regression: a pod readiness flap remounts the Ollie <iframe> into a fresh
  // JS realm, but the old iframe is destroyed without running its React
  // cleanup, leaving a dead-realm explain:run subscriber parked in the host
  // Set. The host then fanned explain:run out to BOTH runners and the orphan
  // emitted a spurious "Couldn't load the explanation." Subscribing a new
  // explain:run/explain:cancel handler must evict the orphan(s) first.
  it.each([
    "explain:run",
    "explain:cancel",
    "chat:continue",
    "conversation:start",
  ] as const)(
    "evicts a stale %s subscriber when a new one subscribes",
    (event) => {
      const listeners = createHostListeners();
      const refs = makeRefs(listeners);
      const bridge = createBridge(refs);

      const stale = vi.fn();
      const live = vi.fn();

      bridge.subscribe(event, stale);
      expect(listeners[event].size).toBe(1);

      bridge.subscribe(event, live);
      // The orphan is gone; only the freshest runner remains.
      expect(listeners[event].size).toBe(1);

      emitHostEvent(refs.listeners, event, { explainId: "x" } as never);
      expect(stale).not.toHaveBeenCalled();
      expect(live).toHaveBeenCalledTimes(1);
    },
  );

  it("does NOT evict multi-subscriber events (context:changed keeps both)", () => {
    const listeners = createHostListeners();
    const refs = makeRefs(listeners);
    const bridge = createBridge(refs);

    const first = vi.fn();
    const second = vi.fn();

    bridge.subscribe("context:changed", first);
    bridge.subscribe("context:changed", second);
    expect(listeners["context:changed"].size).toBe(2);
  });

  it("unsubscribe removes the live subscriber", () => {
    const listeners = createHostListeners();
    const refs = makeRefs(listeners);
    const bridge = createBridge(refs);

    const cb = vi.fn();
    const unsub = bridge.subscribe("explain:run", cb);
    expect(listeners["explain:run"].size).toBe(1);

    unsub();
    expect(listeners["explain:run"].size).toBe(0);
  });
});

describe("assistantBridge — requests made before the console subscribes", () => {
  const setup = (dashboardId?: string) => {
    const refs: BridgeRefs = {
      ...makeRefs(createHostListeners()),
      onRequestVisibility: ref(vi.fn()),
      context: ref({
        dashboard: dashboardId ? { id: dashboardId, kind: "dashboard" } : null,
      } as BridgeRefs["context"]["current"]),
      pendingChartEdit: ref(null),
      pendingConversation: ref(null),
    };
    return { refs, bridge: createBridge(refs) };
  };

  const openDashboard = (refs: BridgeRefs, id: string) => {
    refs.context.current = {
      ...refs.context.current,
      dashboard: { id, kind: "dashboard" },
    };
  };

  afterEach(() => {
    vi.useRealTimers();
  });

  it("opens the sidebar and delivers a conversation once the console subscribes", () => {
    const { refs, bridge } = setup("dash-a");

    bridge.startConversation("hello");
    expect(refs.onRequestVisibility.current).toHaveBeenCalledWith(true);

    const listener = vi.fn();
    bridge.subscribe("conversation:start", listener);
    expect(listener).toHaveBeenCalledWith({ message: "hello" });
  });

  it("hands the console the click's origin and entry id, so BI can match the click to its session", () => {
    const { bridge } = setup("dash-a");

    bridge.startConversation("hello", {
      origin: "describe_widget",
      entryId: "e-1",
    });

    const listener = vi.fn();
    bridge.subscribe("conversation:start", listener);
    expect(listener).toHaveBeenCalledWith({
      message: "hello",
      origin: "describe_widget",
      entryId: "e-1",
    });
  });

  it("drops a request that waited longer than the expiry", () => {
    vi.useFakeTimers();
    const { bridge } = setup("dash-a");

    bridge.startConversation("hello");
    vi.advanceTimersByTime(PENDING_REQUEST_TTL_MS + 1);

    const listener = vi.fn();
    bridge.subscribe("conversation:start", listener);
    expect(listener).not.toHaveBeenCalled();
  });

  it("drops a chart edit parked on another dashboard", () => {
    const { refs, bridge } = setup("dash-a");

    bridge.editChart!({
      widgetId: "w-1",
      title: "Cost",
      spec: {},
      query: { id: "q-1" },
    });
    openDashboard(refs, "dash-b");

    const listener = vi.fn();
    bridge.subscribe("chart:edit", listener);
    expect(listener).not.toHaveBeenCalled();
  });

  it("clears parked requests when another dashboard opens", () => {
    const { refs, bridge } = setup("dash-a");

    bridge.startConversation("hello");
    dropStalePending([refs.pendingConversation!], refs.context.current);
    expect(refs.pendingConversation!.current).not.toBeNull();

    openDashboard(refs, "dash-b");
    dropStalePending([refs.pendingConversation!], refs.context.current);
    expect(refs.pendingConversation!.current).toBeNull();
  });

  it("drops a conversation started outside a dashboard once another project opens", () => {
    const { refs, bridge } = setup();
    refs.context.current = { ...refs.context.current, projectId: "p-1" };

    bridge.startConversation("fix this issue");
    refs.context.current = { ...refs.context.current, projectId: "p-2" };

    const listener = vi.fn();
    bridge.subscribe("conversation:start", listener);
    expect(listener).not.toHaveBeenCalled();
  });
});

describe("assistantBridge — chart editing capability", () => {
  afterEach(() => {
    useExplainStore
      .getState()
      .onConsoleReady({ bridgeVersion: 2, capabilities: [] });
  });

  it("offers chart editing only once the console announces it", () => {
    const bridge = createBridge(makeRefs(createHostListeners()));

    useExplainStore
      .getState()
      .onConsoleReady({ bridgeVersion: 2, capabilities: ["explain"] });
    expect(bridge.canEditChart!()).toBe(false);

    useExplainStore.getState().onConsoleReady({
      bridgeVersion: 3,
      capabilities: ["explain", "charts"],
    });
    expect(bridge.canEditChart!()).toBe(true);
  });

  it("offers Duplicate in Ollie only to a console that handles the duplicate flag", () => {
    const bridge = createBridge(makeRefs(createHostListeners()));

    // A v5 console edits charts but drops `duplicate`: its card would save over the original.
    useExplainStore.getState().onConsoleReady({
      bridgeVersion: 5,
      capabilities: ["explain", "charts"],
    });
    expect(bridge.canEditChart!()).toBe(true);
    expect(bridge.canDuplicateChart!()).toBe(false);

    useExplainStore.getState().onConsoleReady({
      bridgeVersion: 6,
      capabilities: ["explain", "charts"],
    });
    expect(bridge.canDuplicateChart!()).toBe(true);
  });

  it("hides Duplicate in Ollie without chart editing or a known console version", () => {
    const bridge = createBridge(makeRefs(createHostListeners()));

    useExplainStore
      .getState()
      .onConsoleReady({ bridgeVersion: 6, capabilities: ["explain"] });
    expect(bridge.canDuplicateChart!()).toBe(false);

    useExplainStore.setState({
      capabilities: ["explain", "charts"],
      consoleBridgeVersion: null,
    });
    expect(bridge.canDuplicateChart!()).toBe(false);
  });
});

describe("assistantBridge — artifacts", () => {
  const proposal = {
    proposalId: "p1",
    kind: "test_kind",
    target: { id: "a" },
    payload: { value: 1 },
    summary: "done",
  };

  const propose = () => {
    const listeners = createHostListeners();
    const bridge = createBridge(makeRefs(listeners));
    const onResult = vi.fn();
    bridge.subscribe("artifact:result", onResult);
    bridge.emit("artifact:proposed", proposal);
    return onResult;
  };

  it("hands the proposal to the handler for its kind and reports it applied", () => {
    const handler = vi.fn(() => null);
    const unregister = registerArtifactHandler("test_kind", handler);

    const onResult = propose();

    expect(handler).toHaveBeenCalledWith(proposal);
    expect(onResult).toHaveBeenCalledWith({ proposalId: "p1", applied: true });
    unregister();
  });

  it("reports no_handler when nothing is registered for the kind", () => {
    expect(propose()).toHaveBeenCalledWith({
      proposalId: "p1",
      applied: false,
      reason: "no_handler",
    });
  });

  it("reports the handler's rejection", () => {
    const unregister = registerArtifactHandler(
      "test_kind",
      () => "target_mismatch",
    );

    expect(propose()).toHaveBeenCalledWith({
      proposalId: "p1",
      applied: false,
      reason: "target_mismatch",
    });
    unregister();
  });

  it("reports invalid when the handler throws", () => {
    const unregister = registerArtifactHandler("test_kind", () => {
      throw new Error("bad payload");
    });

    expect(propose()).toHaveBeenCalledWith({
      proposalId: "p1",
      applied: false,
      reason: "invalid",
    });
    unregister();
  });

  it("keeps a newer handler when an older one unregisters", () => {
    const unregisterOld = registerArtifactHandler("test_kind", () => "invalid");
    const unregisterNew = registerArtifactHandler("test_kind", () => null);
    unregisterOld();

    expect(propose()).toHaveBeenCalledWith({ proposalId: "p1", applied: true });
    unregisterNew();
  });

  it("tells subscribers when the console announces its capabilities", () => {
    const bridge = createBridge(makeRefs(createHostListeners()));
    const listener = vi.fn();
    const unsubscribe = bridge.subscribeCapabilities!(listener);

    useExplainStore
      .getState()
      .onConsoleReady({ bridgeVersion: 5, capabilities: ["artifacts"] });
    unsubscribe();
    useExplainStore
      .getState()
      .onConsoleReady({ bridgeVersion: 2, capabilities: [] });

    expect(listener).toHaveBeenCalledTimes(1);
  });

  it("offers artifacts only once the console announces it", () => {
    const bridge = createBridge(makeRefs(createHostListeners()));

    useExplainStore
      .getState()
      .onConsoleReady({ bridgeVersion: 4, capabilities: ["charts"] });
    expect(bridge.canProposeArtifacts!()).toBe(false);

    useExplainStore
      .getState()
      .onConsoleReady({ bridgeVersion: 5, capabilities: ["artifacts"] });
    expect(bridge.canProposeArtifacts!()).toBe(true);

    useExplainStore
      .getState()
      .onConsoleReady({ bridgeVersion: 2, capabilities: [] });
  });
});
