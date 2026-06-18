import { create } from "zustand";
import { v4 as uuidv4 } from "uuid";
import {
  BRIDGE_PROTOCOL_VERSION,
  ExplainKind,
  ExplainTarget,
  HostEventMap,
  SidebarEventMap,
} from "@/types/assistant-sidebar";
import { OpikEvent, trackEvent } from "@/lib/analytics/tracking";

export type ConsoleEmit = <E extends keyof HostEventMap>(
  event: E,
  data: HostEventMap[E],
) => void;

export type ExplainPhase = "loading" | "done" | "error";

export interface ExplainEntry {
  explainId: string;
  kind: ExplainKind;
  phase: ExplainPhase;
  text: string;
  error?: string;
  startedAt: number;
  firstTokenAt?: number;
}

// Max concurrent in-flight explains; cached (done) results don't count.
const MAX_IN_FLIGHT = 3;

// Cap on cached entries. Oldest *settled* entries are evicted past this so a
// long session (many cells explained) can't grow the cache without bound.
const MAX_CACHED = 200;

// Scoped by projectId so cached answers / streaming routes can't collide
// across projects. Exported so tests key off the single source of truth.
export const cellKey = (t: ExplainTarget) =>
  `${t.projectId}:${t.kind}:${t.entityId}`;

type ExplainState = {
  // Results cached per cell, so reopening a popover shows the answer (or its
  // still-streaming text) without refetching. Not cleared on close.
  entries: Record<string, ExplainEntry>;
  // explainId -> cell key: routes streamed chunks back to their cell, which is
  // what multiplexes several parallel streams over the single bridge.
  routes: Record<string, string>;
  capabilities: string[];
  // The console's bridge protocol version, from its `console:ready` handshake;
  // null until it arrives. Gates the buttons: we only show Explain when the
  // console is on a bridge at least as new as ours (see useCanExplain), so a
  // protocol skew can't silently no-op the explain events.
  consoleBridgeVersion: number | null;
  // Whether the assistant pod is live (mirrored from useAssistantBackend by the
  // owning sidebar). Gates the buttons together with `capabilities`.
  ready: boolean;
  emit: ConsoleEmit | null;

  setReady: (ready: boolean) => void;
  setEmit: (emit: ConsoleEmit) => void;
  clearEmit: (emit: ConsoleEmit) => void;

  // Returns true if there's now something to show (a fresh stream dispatched,
  // or a cached/in-flight result reused); false when throttled by MAX_IN_FLIGHT.
  explain: (target: ExplainTarget) => boolean;
  retry: (target: ExplainTarget) => void;
  cancel: (target: ExplainTarget) => void;
  continueChat: (target: ExplainTarget, question: string) => void;

  onConsoleReady: (data: SidebarEventMap["console:ready"]) => void;
  onChunk: (data: SidebarEventMap["explain:chunk"]) => void;
  onDone: (data: SidebarEventMap["explain:done"]) => void;
  onError: (data: SidebarEventMap["explain:error"]) => void;
};

const omit = (routes: Record<string, string>, explainId: string) => {
  const next = { ...routes };
  delete next[explainId];
  return next;
};

/**
 * Resolve the cell an `explainId` streams into and apply `patch` to its entry.
 * Terminal events pass `dropRoute` to retire the explainId (no more chunks can
 * arrive) while keeping the cell entry for the cache. No-ops on an unknown id.
 */
const patchEntry = (
  s: ExplainState,
  explainId: string,
  patch: (entry: ExplainEntry) => Partial<ExplainEntry>,
  dropRoute = false,
): ExplainState => {
  const key = s.routes[explainId];
  if (!key) return s;
  const routes = dropRoute ? omit(s.routes, explainId) : s.routes;
  const entry = s.entries[key];
  if (!entry) return dropRoute ? { ...s, routes } : s;
  return {
    ...s,
    routes,
    entries: { ...s.entries, [key]: { ...entry, ...patch(entry) } },
  };
};

// Drop a cell's cached entry and its route so a fresh explain can run.
const removeEntry = (s: ExplainState, key: string): ExplainState => {
  const entry = s.entries[key];
  if (!entry) return s;
  const entries = { ...s.entries };
  delete entries[key];
  return { ...s, entries, routes: omit(s.routes, entry.explainId) };
};

// Evict oldest settled (done/error) entries until there's room for one more,
// leaving in-flight streams untouched. Bounds the cache; see MAX_CACHED.
const evictToCap = (s: ExplainState, cap: number): ExplainState => {
  let count = Object.keys(s.entries).length;
  if (count < cap) return s;
  const oldestFirst = Object.keys(s.entries)
    .filter((k) => s.entries[k].phase !== "loading")
    .sort((a, b) => s.entries[a].startedAt - s.entries[b].startedAt);
  let next = s;
  for (const key of oldestFirst) {
    if (count < cap) break;
    next = removeEntry(next, key);
    count -= 1;
  }
  return next;
};

// Friendly, retryable message shown when all in-flight slots are taken.
const AT_CAPACITY_MESSAGE =
  "Too many explanations in progress. Close one and try again.";

// Build a fresh entry for a cell. Defaults to a "loading" stream; pass a patch
// (e.g. an error) to override.
const makeEntry = (
  target: ExplainTarget,
  patch: Partial<ExplainEntry> = {},
): ExplainEntry => ({
  explainId: uuidv4(),
  kind: target.kind,
  phase: "loading",
  text: "",
  startedAt: performance.now(),
  ...patch,
});

// Insert/replace a cell's entry (evicting to the cache cap first). Tracks a
// route only for entries that will receive streamed chunks.
const withEntry = (
  s: ExplainState,
  key: string,
  entry: ExplainEntry,
  routed: boolean,
): Pick<ExplainState, "entries" | "routes"> => {
  const trimmed = evictToCap(s, MAX_CACHED);
  return {
    entries: { ...trimmed.entries, [key]: entry },
    routes: routed
      ? { ...trimmed.routes, [entry.explainId]: key }
      : trimmed.routes,
  };
};

const useExplainStore = create<ExplainState>((set, get) => ({
  entries: {},
  routes: {},
  capabilities: [],
  consoleBridgeVersion: null,
  ready: false,
  emit: null,

  setReady: (ready) => set({ ready }),
  setEmit: (emit) => set({ emit }),
  // Ownership-guarded (mirrors window.opikBridge): a stale sidebar unmount
  // can't drop a newer instance's channel.
  clearEmit: (emit) => {
    if (get().emit === emit) {
      set({
        emit: null,
        capabilities: [],
        consoleBridgeVersion: null,
        ready: false,
      });
    }
  },

  explain: (target) => {
    const key = cellKey(target);
    const cached = get().entries[key];
    if (cached && cached.phase !== "error") return true; // reuse cache / in-flight

    const inFlight = Object.values(get().entries).filter(
      (e) => e.phase === "loading",
    ).length;
    if (inFlight >= MAX_IN_FLIGHT) {
      // At capacity: surface a clear, retryable error rather than leave the
      // popover stuck on "Thinking…" forever. No stream is dispatched.
      const entry = makeEntry(target, {
        phase: "error",
        error: AT_CAPACITY_MESSAGE,
      });
      set((s) => withEntry(s, key, entry, false));
      return false;
    }

    const entry = makeEntry(target);
    set((s) => withEntry(s, key, entry, true));
    get().emit?.("explain:run", { explainId: entry.explainId, target });
    return true;
  },

  retry: (target) => {
    // If retried while still streaming, stop the old stream before replacing it
    // (mirrors cancel()). Today Retry only shows after an error, but this keeps
    // the action safe if it's ever invoked mid-stream.
    const prev = get().entries[cellKey(target)];
    if (prev?.phase === "loading") {
      get().emit?.("explain:cancel", { explainId: prev.explainId });
    }
    set((s) => removeEntry(s, cellKey(target)));
    get().explain(target);
  },

  // Stop an in-flight stream and reset the cell so reopening starts a fresh
  // explain. No-op unless the cell is mid-stream — cached done/error entries
  // stay put. Without the reset, `explain()` would short-circuit on the stale
  // "loading" entry and the popover would pulse "Thinking…" forever.
  cancel: (target) => {
    const key = cellKey(target);
    const entry = get().entries[key];
    if (!entry || entry.phase !== "loading") return;
    get().emit?.("explain:cancel", { explainId: entry.explainId });
    set((s) => removeEntry(s, key));
  },

  continueChat: (target, question) => {
    const entry = get().entries[cellKey(target)];
    // Offered as soon as any text has streamed in (done OR still loading);
    // refuse on empty / errored cells.
    if (!entry || entry.phase === "error" || entry.text.length === 0) return;
    // Mid-stream continue: the chat takes over in the sidebar, so stop the
    // (paid) cell stream and freeze the partial text as the cached answer.
    // Dropping the route here also makes the popover-close cancel() a no-op
    // (phase is now "done"), so the stream isn't cancelled twice.
    if (entry.phase === "loading") {
      get().emit?.("explain:cancel", { explainId: entry.explainId });
      set((s) =>
        patchEntry(s, entry.explainId, () => ({ phase: "done" }), true),
      );
    }
    get().emit?.("chat:continue", { question, answer: entry.text, target });
  },

  onConsoleReady: ({ bridgeVersion, capabilities }) =>
    set({ capabilities, consoleBridgeVersion: bridgeVersion }),

  onChunk: ({ explainId, delta }) =>
    set((s) =>
      patchEntry(s, explainId, (e) => ({
        phase: "loading",
        text: e.text + delta,
        firstTokenAt: e.firstTokenAt ?? performance.now(),
      })),
    ),

  // Completion/error telemetry fires from the store (once per stream, keyed by
  // the still-present route) — the popover's lifecycle doesn't match the
  // stream's, so reporting from the UI would double-count or miss events.
  onDone: ({ explainId }) => {
    const entry = get().entries[get().routes[explainId]];
    if (entry) {
      trackEvent(OpikEvent.EXPLAIN_COMPLETED, {
        kind: entry.kind,
        ttft_ms: entry.firstTokenAt
          ? Math.round(entry.firstTokenAt - entry.startedAt)
          : null,
      });
    }
    set((s) => patchEntry(s, explainId, () => ({ phase: "done" }), true));
  },

  onError: ({ explainId, message }) => {
    const entry = get().entries[get().routes[explainId]];
    if (entry) {
      trackEvent(OpikEvent.EXPLAIN_ERRORED, { kind: entry.kind });
    }
    set((s) =>
      patchEntry(
        s,
        explainId,
        () => ({ phase: "error", error: message }),
        true,
      ),
    );
  },
}));

/**
 * Forward a shell→host bridge event to the explain store. Returns false when
 * `event` isn't an explain event, so the bridge falls through to its default
 * handling. Keeps the explain event shapes (and the casts at the untyped bridge
 * boundary) owned by this module rather than leaking into the sidebar.
 */
export const handleConsoleEvent = (
  event: keyof SidebarEventMap,
  data: SidebarEventMap[keyof SidebarEventMap],
): boolean => {
  const store = useExplainStore.getState();
  switch (event) {
    case "console:ready":
      store.onConsoleReady(data as SidebarEventMap["console:ready"]);
      return true;
    case "explain:chunk":
      store.onChunk(data as SidebarEventMap["explain:chunk"]);
      return true;
    case "explain:done":
      store.onDone(data as SidebarEventMap["explain:done"]);
      return true;
    case "explain:error":
      store.onError(data as SidebarEventMap["explain:error"]);
      return true;
    default:
      return false;
  }
};

export const useExplainEntry = (target: ExplainTarget) =>
  useExplainStore((s) => s.entries[cellKey(target)]);

// Single gate selector, read by the (per-row) button — no per-row backend
// hooks. Show Explain only when the whole Olli integration is actually usable:
//   1. pod live (`ready`),
//   2. the console advertised the "explain" capability (its remote on/off —
//      old/incapable builds simply omit it), and
//   3. the console's bridge is at least as new as ours, so the explain events
//      we emit are understood (a skew would otherwise silently no-op). Newer
//      consoles stay compatible (old contracts are kept), hence `>=`.
export const useCanExplain = () =>
  useExplainStore(
    (s) =>
      // Bridge connected (clearEmit wipes the rest atomically, but checking
      // emit makes "needs a live bridge" explicit and self-documenting)...
      s.emit !== null &&
      // ...pod live...
      s.ready &&
      // ...console advertised the "explain" capability (its remote on/off)...
      s.capabilities.includes("explain") &&
      // ...and its bridge is at least as new as ours (newer stays compatible).
      s.consoleBridgeVersion !== null &&
      s.consoleBridgeVersion >= BRIDGE_PROTOCOL_VERSION,
  );

export default useExplainStore;
