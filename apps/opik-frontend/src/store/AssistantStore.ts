import { create } from "zustand";
import { v4 as uuidv4 } from "uuid";
import {
  ASSISTANT_SIDEBAR_COLLAPSED_WIDTH,
  getStoredAssistantSidebarWidth,
  isAssistantSidebarOpen,
  setAssistantSidebarOpen,
} from "@/constants/assistantSidebar";
import {
  ExplainTarget,
  HostEventMap,
  SidebarEventMap,
} from "@/types/assistant-sidebar";

/**
 * Console lifecycle from the host's point of view. This is NOT the backend pod
 * phase (`useAssistantBackend`) — it reflects whether the iframe console has
 * completed its `console:ready` handshake, which is what gates Explain
 * buttons. Mapping pod phase → this status is the owning comet
 * `AssistantSidebar`'s job (`setStatus`); `console:ready` is the only thing
 * that promotes to `"ready"`.
 *
 * - `unavailable`: OSS / no comet sidebar mounted / Ollie disabled. Default —
 *   nobody writes the store, so Explain buttons never render (the OSS boundary).
 * - `waking`: pod provisioning or cold-starting, or the iframe console has not
 *   yet emitted `console:ready`. Drives the §5 bounded "waking up" popover
 *   state, distinct from the in-flight thinking pulse.
 * - `ready`: console handshake done; `capabilities` are known.
 * - `error`: backend or console error.
 */
export type AssistantConsoleStatus =
  | "unavailable"
  | "waking"
  | "ready"
  | "error";

/** Lifecycle of a single explain request, keyed by `explainId`. */
export type ExplainPhase = "thinking" | "streaming" | "done" | "error";

export interface ExplainState {
  explainId: string;
  target: ExplainTarget;
  phase: ExplainPhase;
  /** Streamed `explain:chunk` deltas, concatenated. */
  text: string;
  /** Set only when `phase === "error"`. */
  error: string | null;
}

/**
 * Host → shell emitter, registered by the owning comet `AssistantSidebar`.
 * Generic over `HostEventMap` so `emitToConsole` is type-checked against the
 * bridge contract.
 */
export type ConsoleEmit = <E extends keyof HostEventMap>(
  event: E,
  data: HostEventMap[E],
) => void;

/**
 * Hard cap on concurrent in-flight explains — a client-side rate-limit / cost
 * guard. `runExplain` returns `null` once this many are `thinking`/`streaming`
 * (the §5 "at-cap" state).
 */
export const MAX_CONCURRENT_EXPLAINS = 3;

interface QueuedEmit {
  event: keyof HostEventMap;
  data: HostEventMap[keyof HostEventMap];
}

type AssistantStore = {
  // ── sidebar open/width (FE-1a) ──────────────────────────────────────────
  // Seeded from localStorage on init. The store does NOT write these keys
  // itself: the iframe console writes `assistant-sidebar-width` directly and
  // the `setAssistantSidebarOpen()` helper (via `openSidebar`) owns the
  // `assistant-sidebar-open` key. The seed is what survives iframe remounts
  // and page refreshes (VER-9).
  sidebarWidth: number;
  setSidebarWidth: (width: number) => void;
  openSidebar: () => void;

  // ── console lifecycle + capabilities (FE-1b) ────────────────────────────
  status: AssistantConsoleStatus;
  capabilities: string[];
  setStatus: (status: AssistantConsoleStatus) => void;

  // ── ownership-guarded host → shell emit (FE-1b) ─────────────────────────
  consoleEmit: ConsoleEmit | null;
  emitQueue: QueuedEmit[];
  /** Claim ownership of the host→shell channel (called on sidebar mount). */
  registerConsoleEmit: (emit: ConsoleEmit) => void;
  /**
   * Release ownership (called on sidebar unmount). Ownership-guarded: a stale
   * unmount whose `emit` no longer matches the current owner is a no-op, so it
   * cannot clobber a freshly mounted instance (the sidebar↔OlliePage switch).
   */
  unregisterConsoleEmit: (emit: ConsoleEmit) => void;
  /** Emit a host event now if ready, else queue it until `console:ready`. */
  emitToConsole: ConsoleEmit;

  // ── explain state, keyed by explainId (FE-1b) ───────────────────────────
  explains: Record<string, ExplainState>;
  /** Mint an explainId, enforce the concurrency cap, emit `explain:run`.
   * Returns the id, or `null` when at cap. */
  runExplain: (target: ExplainTarget) => string | null;
  /** Emit `explain:cancel` and drop the entry (terminal — frees a cap slot). */
  cancelExplain: (explainId: string) => void;
  /** Drop a finished entry so the explains map doesn't grow unbounded
   * (called on popover unmount). Idempotent. */
  clearExplain: (explainId: string) => void;

  // ── bridge → store routing (FE-2; called from AssistantSidebar.emit) ─────
  applyConsoleReady: (data: SidebarEventMap["console:ready"]) => void;
  applyExplainChunk: (data: SidebarEventMap["explain:chunk"]) => void;
  applyExplainDone: (data: SidebarEventMap["explain:done"]) => void;
  applyExplainError: (data: SidebarEventMap["explain:error"]) => void;
};

const countActiveExplains = (explains: Record<string, ExplainState>): number =>
  Object.values(explains).filter(
    (e) => e.phase === "thinking" || e.phase === "streaming",
  ).length;

const useAssistantStore = create<AssistantStore>((set, get) => ({
  // ── sidebar open/width ──────────────────────────────────────────────────
  sidebarWidth: isAssistantSidebarOpen()
    ? getStoredAssistantSidebarWidth()
    : ASSISTANT_SIDEBAR_COLLAPSED_WIDTH,
  setSidebarWidth: (width) => set({ sidebarWidth: width }),
  openSidebar: () => {
    setAssistantSidebarOpen(true);
    set({ sidebarWidth: getStoredAssistantSidebarWidth() });
  },

  // ── console lifecycle + capabilities ────────────────────────────────────
  status: "unavailable",
  capabilities: [],
  setStatus: (status) => set({ status }),

  // ── ownership-guarded host → shell emit ─────────────────────────────────
  consoleEmit: null,
  emitQueue: [],
  registerConsoleEmit: (emit) => set({ consoleEmit: emit }),
  unregisterConsoleEmit: (emit) => {
    // Guard: only the current owner may tear down (sidebar↔OlliePage switch).
    if (get().consoleEmit !== emit) return;
    // Drop in-flight explains too: the next console has no knowledge of these
    // explainIds, so it will never emit their chunk/done/error — leaving them
    // stuck in `thinking`/`streaming` and permanently consuming cap slots.
    set({
      consoleEmit: null,
      emitQueue: [],
      status: "unavailable",
      capabilities: [],
      explains: {},
    });
  },
  emitToConsole: (event, data) => {
    const { status, consoleEmit } = get();
    if (status === "ready" && consoleEmit) {
      consoleEmit(event, data);
    } else {
      // Buffer until the console handshake completes so e.g. a `chat:continue`
      // fired mid-load isn't dropped.
      set((s) => ({ emitQueue: [...s.emitQueue, { event, data }] }));
    }
  },

  // ── explain state ───────────────────────────────────────────────────────
  explains: {},
  runExplain: (target) => {
    if (countActiveExplains(get().explains) >= MAX_CONCURRENT_EXPLAINS) {
      return null;
    }
    const explainId = uuidv4();
    set((s) => ({
      explains: {
        ...s.explains,
        [explainId]: {
          explainId,
          target,
          phase: "thinking",
          text: "",
          error: null,
        },
      },
    }));
    get().emitToConsole("explain:run", { explainId, target });
    return explainId;
  },
  cancelExplain: (explainId) => {
    get().emitToConsole("explain:cancel", { explainId });
    set((s) => {
      if (!s.explains[explainId]) return s;
      const next = { ...s.explains };
      delete next[explainId];
      return { explains: next };
    });
  },
  clearExplain: (explainId) => {
    set((s) => {
      if (!s.explains[explainId]) return s;
      const next = { ...s.explains };
      delete next[explainId];
      return { explains: next };
    });
  },

  // ── bridge → store routing ──────────────────────────────────────────────
  applyConsoleReady: ({ capabilities }) => {
    const { consoleEmit, emitQueue } = get();
    set({ status: "ready", capabilities, emitQueue: [] });
    // Flush anything buffered while the console was still loading.
    if (consoleEmit) {
      for (const queued of emitQueue) {
        consoleEmit(queued.event, queued.data);
      }
    }
  },
  applyExplainChunk: ({ explainId, delta }) => {
    set((s) => {
      const current = s.explains[explainId];
      // Ignore deltas for an unknown / already-cleared explain (cancel race).
      if (!current) return s;
      return {
        explains: {
          ...s.explains,
          [explainId]: {
            ...current,
            phase: "streaming",
            text: current.text + delta,
          },
        },
      };
    });
  },
  applyExplainDone: ({ explainId }) => {
    set((s) => {
      const current = s.explains[explainId];
      if (!current) return s;
      // Empty body stays `done` with empty text — the popover renders the §5
      // "No explanation available" fallback, not a separate store phase.
      return {
        explains: {
          ...s.explains,
          [explainId]: { ...current, phase: "done" },
        },
      };
    });
  },
  applyExplainError: ({ explainId, message }) => {
    set((s) => {
      const current = s.explains[explainId];
      if (!current) return s;
      return {
        explains: {
          ...s.explains,
          [explainId]: { ...current, phase: "error", error: message },
        },
      };
    });
  },
}));

// ── selector hooks (AppStore convention) ──────────────────────────────────
export const useAssistantSidebarWidth = () =>
  useAssistantStore((state) => state.sidebarWidth);

export const useIsAssistantSidebarOpen = () =>
  useAssistantStore(
    (state) => state.sidebarWidth > ASSISTANT_SIDEBAR_COLLAPSED_WIDTH,
  );

export const useSetAssistantSidebarWidth = () =>
  useAssistantStore((state) => state.setSidebarWidth);

export const useOpenAssistantSidebar = () =>
  useAssistantStore((state) => state.openSidebar);

export const useAssistantStatus = () =>
  useAssistantStore((state) => state.status);

export const useAssistantCapabilities = () =>
  useAssistantStore((state) => state.capabilities);

export const useExplainState = (explainId: string | null) =>
  useAssistantStore((state) =>
    explainId ? state.explains[explainId] : undefined,
  );

export default useAssistantStore;
