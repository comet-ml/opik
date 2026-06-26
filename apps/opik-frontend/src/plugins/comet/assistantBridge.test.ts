import { describe, expect, it, vi } from "vitest";
import type { MutableRefObject } from "react";
import {
  createBridge,
  createHostListeners,
  emitHostEvent,
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
