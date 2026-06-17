import { create } from "zustand";
import { v4 as uuidv4 } from "uuid";
import {
  ExplainTarget,
  HostEventMap,
  SidebarEventMap,
} from "@/types/assistant-sidebar";

export type ConsoleEmit = <E extends keyof HostEventMap>(
  event: E,
  data: HostEventMap[E],
) => void;

export type ExplainPhase = "loading" | "done" | "error";

export interface ExplainEntry {
  explainId: string;
  phase: ExplainPhase;
  text: string;
  error?: string;
}

// Max concurrent in-flight explains; cached (done) results don't count.
const MAX_IN_FLIGHT = 4;

// Scoped by projectId so cached answers / streaming routes can't collide
// across projects.
const cellKey = (t: ExplainTarget) => `${t.projectId}:${t.kind}:${t.entityId}`;

type ExplainState = {
  // Results cached per cell, so reopening a popover shows the answer (or its
  // still-streaming text) without refetching. Not cleared on close.
  entries: Record<string, ExplainEntry>;
  // explainId -> cell key: routes streamed chunks back to their cell, which is
  // what multiplexes several parallel streams over the single bridge.
  routes: Record<string, string>;
  capabilities: string[];
  emit: ConsoleEmit | null;

  setEmit: (emit: ConsoleEmit) => void;
  clearEmit: (emit: ConsoleEmit) => void;

  explain: (target: ExplainTarget) => void;
  retry: (target: ExplainTarget) => void;
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

const useExplainStore = create<ExplainState>((set, get) => ({
  entries: {},
  routes: {},
  capabilities: [],
  emit: null,

  setEmit: (emit) => set({ emit }),
  // Ownership-guarded (mirrors window.opikBridge): a stale sidebar unmount
  // can't drop a newer instance's channel.
  clearEmit: (emit) => {
    if (get().emit === emit) set({ emit: null, capabilities: [] });
  },

  explain: (target) => {
    const key = cellKey(target);
    const cached = get().entries[key];
    if (cached && cached.phase !== "error") return; // reuse cache / in-flight
    const inFlight = Object.values(get().entries).filter(
      (e) => e.phase === "loading",
    ).length;
    if (inFlight >= MAX_IN_FLIGHT) return;
    const explainId = uuidv4();
    set((s) => ({
      entries: {
        ...s.entries,
        [key]: { explainId, phase: "loading", text: "" },
      },
      routes: { ...s.routes, [explainId]: key },
    }));
    get().emit?.("explain:run", { explainId, target });
  },

  retry: (target) => {
    set((s) => removeEntry(s, cellKey(target)));
    get().explain(target);
  },

  continueChat: (target, question) => {
    const entry = get().entries[cellKey(target)];
    if (!entry || entry.phase !== "done") return;
    get().emit?.("chat:continue", { question, answer: entry.text, target });
  },

  onConsoleReady: ({ capabilities }) => set({ capabilities }),

  onChunk: ({ explainId, delta }) =>
    set((s) =>
      patchEntry(s, explainId, (e) => ({
        phase: "loading",
        text: e.text + delta,
      })),
    ),

  onDone: ({ explainId }) =>
    set((s) => patchEntry(s, explainId, () => ({ phase: "done" }), true)),

  onError: ({ explainId, message }) =>
    set((s) =>
      patchEntry(
        s,
        explainId,
        () => ({ phase: "error", error: message }),
        true,
      ),
    ),
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

export const useIsExplainCapable = () =>
  useExplainStore((s) => s.capabilities.includes("explain"));

export default useExplainStore;
