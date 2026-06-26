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
import { explainLog, shortId } from "@/plugins/comet/explain/explainDebug";

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

export const createBridge = (refs: BridgeRefs): AssistantSidebarBridge => ({
  version: BRIDGE_PROTOCOL_VERSION,
  getContext: () => refs.context.current,
  subscribe: (event, callback) => {
    const set = refs.listeners.current[event as keyof HostEventMap] as
      | Set<typeof callback>
      | undefined;
    if (!set) return () => {};
    set.add(callback);
    // Subscriber count per host→shell event. For "explain:run" a size ≥2 means
    // more than one console runner is subscribed (the duplicate-runner bug).
    explainLog("bridge", `subscribe "${event}" → set.size=${set.size}`);

    // Replay latest runner state to late subscribers (e.g. Ollie iframe loaded after FE)
    if (event === "runner:state-changed" && refs.lastRunnerState.current) {
      (callback as (data: RunnerBridgeState) => void)(
        refs.lastRunnerState.current,
      );
    }

    return () => {
      set.delete(callback);
      explainLog("bridge", `unsubscribe "${event}" → set.size=${set.size}`);
    };
  },
  emit: (event, data) => {
    // INBOUND (shell → host). The explain relay (console:ready / explain:chunk
    // / explain:done / explain:error) flows through the `default` branch into
    // the store; logging it here is the raw wire, before any interpretation.
    if (typeof event === "string" && event.startsWith("explain:")) {
      const d = (data ?? {}) as {
        explainId?: string;
        code?: string;
        message?: string;
        delta?: string;
      };
      explainLog(
        "bridge",
        `◀ IN  "${event}" id=${shortId(d.explainId)}` +
          (d.code !== undefined ? ` code=${d.code}` : "") +
          (d.message !== undefined ? ` msg=${JSON.stringify(d.message)}` : "") +
          (d.delta !== undefined ? ` deltaLen=${d.delta.length}` : ""),
      );
    } else if (event === "console:ready") {
      explainLog("bridge", `◀ IN  "console:ready"`, data);
    }
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
  const set = listenersRef.current[event];
  // OUTBOUND (host → shell). For explain:run/cancel, a set.size of 0 means the
  // event reaches NO console runner (false timeout); ≥2 means a duplicate
  // runner will double-handle it (the spurious-error suspect).
  if (typeof event === "string" && event.startsWith("explain:")) {
    const d = (data ?? {}) as {
      explainId?: string;
      target?: { kind?: string };
    };
    explainLog(
      "bridge",
      `▶ OUT "${event}" id=${shortId(d.explainId)}` +
        (d.target?.kind ? ` kind=${d.target.kind}` : "") +
        ` → listeners=${set.size}`,
    );
  }
  for (const listener of set) {
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
    explainLog("sidebar", "register explain emitter (setEmit)");
    useExplainStore.getState().setEmit(emit);
    return () => {
      explainLog("sidebar", "unregister explain emitter (clearEmit)");
      useExplainStore.getState().clearEmit(emit);
    };
  }, [listenersRef]);
}
