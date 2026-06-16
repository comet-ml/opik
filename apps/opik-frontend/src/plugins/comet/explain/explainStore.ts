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
const keyOf = (t: ExplainTarget) => `${t.projectId}:${t.kind}:${t.entityId}`;

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

  onConsoleReady: (data: SidebarEventMap["console:ready"]) => void;
  onChunk: (data: SidebarEventMap["explain:chunk"]) => void;
  onDone: (data: SidebarEventMap["explain:done"]) => void;
  onError: (data: SidebarEventMap["explain:error"]) => void;
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
    const key = keyOf(target);
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
    const key = keyOf(target);
    set((s) => {
      const entries = { ...s.entries };
      const routes = { ...s.routes };
      if (entries[key]) delete routes[entries[key].explainId];
      delete entries[key];
      return { entries, routes };
    });
    get().explain(target);
  },

  onConsoleReady: ({ capabilities }) => set({ capabilities }),

  onChunk: ({ explainId, delta }) =>
    set((s) => {
      const entry = s.entries[s.routes[explainId]];
      if (!entry) return s; // unknown / cleared
      return {
        entries: {
          ...s.entries,
          [s.routes[explainId]]: {
            ...entry,
            phase: "loading",
            text: entry.text + delta,
          },
        },
      };
    }),

  // A terminal event drops the explainId route (it can't receive more chunks);
  // the cell entry stays for the cache. A retry mints a fresh explainId+route.
  onDone: ({ explainId }) =>
    set((s) => {
      const key = s.routes[explainId];
      if (!key) return s; // unknown / already dropped
      const routes = { ...s.routes };
      delete routes[explainId];
      const entry = s.entries[key];
      return entry
        ? {
            routes,
            entries: { ...s.entries, [key]: { ...entry, phase: "done" } },
          }
        : { routes };
    }),

  onError: ({ explainId, message }) =>
    set((s) => {
      const key = s.routes[explainId];
      if (!key) return s;
      const routes = { ...s.routes };
      delete routes[explainId];
      const entry = s.entries[key];
      return entry
        ? {
            routes,
            entries: {
              ...s.entries,
              [key]: { ...entry, phase: "error", error: message },
            },
          }
        : { routes };
    }),
}));

export const useExplainEntry = (target: ExplainTarget) =>
  useExplainStore((s) => s.entries[keyOf(target)]);

export const useIsExplainCapable = () =>
  useExplainStore((s) => s.capabilities.includes("explain"));

export default useExplainStore;
