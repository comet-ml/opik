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
    dropStalePending([refs.pendingConversation!], "dash-a");
    expect(refs.pendingConversation!.current).not.toBeNull();

    dropStalePending([refs.pendingConversation!], "dash-b");
    expect(refs.pendingConversation!.current).toBeNull();
  });
});
