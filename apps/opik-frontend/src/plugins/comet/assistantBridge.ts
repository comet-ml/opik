import { useEffect, useRef, type MutableRefObject } from "react";
import {
  AssistantSidebarBridge,
  BRIDGE_PROTOCOL_VERSION,
  BridgeContext,
  HostEventMap,
  RunnerBridgeState,
  SidebarEventMap,
} from "@/types/assistant-sidebar";
import { IS_ASSISTANT_DEV } from "@/plugins/comet/constants/assistant";
import useExplainStore, {
  ConsoleEmit,
  handleConsoleEvent,
} from "@/plugins/comet/explain/explainStore";

/**
 * Host side of the assistant bridge: the in-page channel the Ollie console
 * (iframe) talks to via `window.opikBridge`. `createBridge` builds the bridge
 * object the console consumes — host→shell events fan out to subscribers, and
 * shell→host events are dispatched to host handlers (the explain relay is owned
 * by the explain store via `handleConsoleEvent`). The owning `AssistantSidebar`
 * component supplies the live refs and wires these into React effects.
 */

/** Keeps a ref whose `.current` always points to the latest value. */
export function useLatestRef<T>(value: T): MutableRefObject<T> {
  const ref = useRef(value);
  ref.current = value;
  return ref;
}

/** A subscriber set per host→shell event the console can listen to. */
export type HostListeners = {
  [K in keyof HostEventMap]: Set<(data: HostEventMap[K]) => void>;
};

export function createHostListeners(): HostListeners {
  return {
    "context:changed": new Set(),
    "visibility:changed": new Set(),
    "runner:state-changed": new Set(),
    "conversation:start": new Set(),
    "explain:run": new Set(),
    "explain:cancel": new Set(),
    "chat:continue": new Set(),
  };
}

/** The live values `createBridge` reads through (kept current by the component). */
export interface BridgeRefs {
  navigate: MutableRefObject<
    (path: string, search?: Record<string, unknown>) => void
  >;
  onWidthChange: MutableRefObject<(width: number) => void>;
  onNotification: MutableRefObject<
    (data: SidebarEventMap["notification"]) => void
  >;
  onRequestVisibility: MutableRefObject<(open: boolean) => void>;
  onRequestPair: MutableRefObject<
    (data: SidebarEventMap["runner:request-pair"]) => void
  >;
  context: MutableRefObject<BridgeContext>;
  listeners: MutableRefObject<HostListeners>;
  lastRunnerState: MutableRefObject<RunnerBridgeState | null>;
}

/**
 * Host→shell events handled by exactly ONE live console subscriber. A second
 * subscription to any of these is never legitimate: it means a previous iframe
 * generation never tore down (see the eviction note in `subscribe`).
 *
 * Deliberately EXCLUDES context:changed / runner:state-changed /
 * visibility:changed — those legitimately fan out to several subscribers within
 * a single console, and runner-state relies on the late-subscriber replay, so a
 * count >1 there is normal and must not be cleared.
 */
const SINGLE_SUBSCRIBER_EVENTS: ReadonlySet<keyof HostEventMap> = new Set([
  "explain:run",
  "explain:cancel",
  "chat:continue",
  "conversation:start",
]);

export const createBridge = (refs: BridgeRefs): AssistantSidebarBridge => ({
  version: BRIDGE_PROTOCOL_VERSION,
  getContext: () => refs.context.current,
  subscribe: (event, callback) => {
    const set = refs.listeners.current[event as keyof HostEventMap] as
      | Set<typeof callback>
      | undefined;
    if (!set) return () => {};

    // SINGLE_SUBSCRIBER_EVENTS are handled by exactly one live console handler.
    // A second subscription means a previous iframe generation never tore down:
    // a pod readiness flap toggles `isBackendReady`, which remounts the <iframe>
    // into a fresh JS realm, but the old iframe's document is destroyed WITHOUT
    // running the console's React cleanup, so its subscription closures stay
    // parked in these Sets (the host component, still mounted, never recreated
    // them). Left in place, the host fans each event out to BOTH the live
    // handler AND the orphaned dead-realm closure, which fails fast against its
    // torn-down fetch context — surfacing as a spurious "Couldn't load the
    // explanation." (explain:run) or a "Couldn't continue the conversation."
    // toast (chat:continue), even though the live handler succeeded. Evict the
    // orphan(s) before adding the freshest handler so only one is ever wired.
    if (
      SINGLE_SUBSCRIBER_EVENTS.has(event as keyof HostEventMap) &&
      set.size > 0
    ) {
      set.clear();
    }

    set.add(callback);

    // Replay latest runner state to late subscribers (e.g. Ollie iframe loaded after FE)
    if (event === "runner:state-changed" && refs.lastRunnerState.current) {
      (callback as (data: RunnerBridgeState) => void)(
        refs.lastRunnerState.current,
      );
    }

    return () => {
      set.delete(callback);
    };
  },
  emit: (event, data) => {
    switch (event) {
      case "navigate": {
        const { path, search } = data as SidebarEventMap["navigate"];
        refs.navigate.current(path, search);
        break;
      }
      case "sidebar:resized":
        refs.onWidthChange.current(
          (data as SidebarEventMap["sidebar:resized"]).width,
        );
        break;
      case "notification":
        refs.onNotification.current(data as SidebarEventMap["notification"]);
        break;
      case "sidebar:request-open":
        refs.onRequestVisibility.current(true);
        break;
      case "sidebar:request-close":
        refs.onRequestVisibility.current(false);
        break;
      case "runner:request-pair":
        refs.onRequestPair.current(
          data as SidebarEventMap["runner:request-pair"],
        );
        break;
      default:
        // The explain relay (shell → host) is owned by the explain module; the
        // bridge just forwards anything it doesn't handle itself.
        if (!handleConsoleEvent(event, data) && IS_ASSISTANT_DEV) {
          console.warn(
            `[AssistantBridge] Unhandled sidebar event: "${event}"`,
            data,
          );
        }
    }
  },
  startConversation: (message: string) => {
    emitHostEvent(refs.listeners, "conversation:start", { message });
  },
});

/** Emit a host event to all subscribed sidebar listeners. */
export function emitHostEvent<E extends keyof HostEventMap>(
  listenersRef: MutableRefObject<HostListeners>,
  event: E,
  data: HostEventMap[E],
) {
  for (const listener of listenersRef.current[event]) {
    (listener as (d: HostEventMap[E]) => void)(data);
  }
}

/**
 * Registers this sidebar as the explain bridge's host→shell emitter for its
 * lifetime, tearing it down (ownership-guarded) on unmount so the
 * sidebar↔OlliePage instance switch can't drop a freshly mounted instance.
 */
export function useRegisterExplainEmitter(
  listenersRef: MutableRefObject<HostListeners>,
) {
  useEffect(() => {
    const emit: ConsoleEmit = (event, data) =>
      emitHostEvent(listenersRef, event, data);
    useExplainStore.getState().setEmit(emit);
    return () => useExplainStore.getState().clearEmit(emit);
  }, [listenersRef]);
}
