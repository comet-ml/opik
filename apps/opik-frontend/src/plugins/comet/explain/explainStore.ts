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
import { createStreamWatchdog } from "./streamWatchdog";

// ─────────────────────────────────────────────────────────────────────────────
// Types
// ─────────────────────────────────────────────────────────────────────────────

/** Emits a host→shell bridge event (registered by the owning AssistantSidebar). */
export type ConsoleEmit = <E extends keyof HostEventMap>(
  event: E,
  data: HostEventMap[E],
) => void;

// `waking` is a slow-to-start `loading` (cold pod) that shows a different hint;
// both count as in-flight everywhere `isPending` is checked.
export type ExplainPhase = "loading" | "waking" | "done" | "error";

export interface ExplainEntry {
  explainId: string;
  kind: ExplainKind;
  phase: ExplainPhase;
  text: string;
  error?: string;
  /** Machine-readable error reason → contextual copy; absent on success. */
  code?: string;
  startedAt: number;
  firstTokenAt?: number;
}

const isPending = (phase: ExplainPhase) =>
  phase === "loading" || phase === "waking";

// ─────────────────────────────────────────────────────────────────────────────
// Tunables
// ─────────────────────────────────────────────────────────────────────────────

const MAX_IN_FLIGHT = 3; // concurrent streams; cached (settled) results don't count
const MAX_CACHED = 200; // cached cells; oldest settled ones evicted past this
const WAKING_MS = 10_000; // no chunk yet → swap "Thinking…" for a "waking" hint
const TIMEOUT_MS = 30_000; // still no chunk → give up with a retryable error

// ─────────────────────────────────────────────────────────────────────────────
// Error copy
// ─────────────────────────────────────────────────────────────────────────────

// Friendly, contextual copy per error code. The console may send a `code` with
// explain:error; the watchdog/pod-loss paths set their own.
const ERROR_COPY = {
  waking: "Ollie is waking up — give it a moment and retry.",
  timeout: "Ollie took too long to respond. Try again.",
  unavailable: "Ollie is unavailable right now. Try again shortly.",
  rate_limited: "Too many requests right now. Try again in a moment.",
} as const;
type ErrorCode = keyof typeof ERROR_COPY;

const AT_CAPACITY =
  "Too many explanations in progress. Close one and try again.";

// Known code → copy; otherwise the raw upstream message, then a generic line.
// `code` is a free-form string off the bridge, so guard the lookup with an
// own-property check: a bare `ERROR_COPY[code]` would resolve inherited
// Object.prototype members ("constructor"/"toString"/…) to Functions, which are
// truthy and would short-circuit the fallback — then render as a React child
// and crash the popover.
const errorMessage = (code: string | undefined, raw: string | undefined) =>
  (code && Object.prototype.hasOwnProperty.call(ERROR_COPY, code)
    ? ERROR_COPY[code as ErrorCode]
    : undefined) ??
  raw ??
  "Something went wrong.";

// ─────────────────────────────────────────────────────────────────────────────
// Cell identity
// ─────────────────────────────────────────────────────────────────────────────

// Scoped by projectId so cached answers / routes can't collide across projects.
// Exported so tests key off the single source of truth.
export const cellKey = (t: ExplainTarget) =>
  `${t.projectId}:${t.kind}:${t.entityId}`;

// ─────────────────────────────────────────────────────────────────────────────
// Entry lifecycle (pure)
// ─────────────────────────────────────────────────────────────────────────────

const startEntry = (target: ExplainTarget): ExplainEntry => ({
  explainId: uuidv4(),
  kind: target.kind,
  phase: "loading",
  text: "",
  startedAt: performance.now(),
});

const erroredEntry = (target: ExplainTarget, error: string): ExplainEntry => ({
  ...startEntry(target),
  phase: "error",
  error,
});

// A chunk is authoritative proof the stream is alive, so it always recovers the
// cell to `loading`. When it arrives *after* an error (a console retry of a
// transient failure succeeded), it restarts the answer from scratch — reset the
// text + first-token time and clear the stale error rather than appending.
const withChunk = (entry: ExplainEntry, delta: string): ExplainEntry => {
  const recovering = entry.phase === "error";
  return {
    ...entry,
    phase: "loading",
    error: undefined,
    code: undefined,
    text: (recovering ? "" : entry.text) + delta,
    firstTokenAt: recovering
      ? performance.now()
      : entry.firstTokenAt ?? performance.now(),
  };
};

// ─────────────────────────────────────────────────────────────────────────────
// Cache (pure): cells keyed by cellKey, plus an explainId→cellKey route index
// that multiplexes N concurrent streams back to their cells over the one bridge.
//
// A route lives from dispatch until the stream is `done`, retried, or its cell is
// evicted — crucially NOT dropped on error, so a late recovery chunk can still
// reach the cell (see `withChunk`). It stays bounded by being coupled 1:1 to a
// cell entry, which the cache cap evicts.
// ─────────────────────────────────────────────────────────────────────────────

interface Cache {
  entries: Record<string, ExplainEntry>;
  routes: Record<string, string>;
}

const without = <T>(record: Record<string, T>, key: string) => {
  const next = { ...record };
  delete next[key];
  return next;
};

const cellOf = (c: Cache, explainId: string): ExplainEntry | undefined => {
  const key = c.routes[explainId];
  return key ? c.entries[key] : undefined;
};

const inFlightCount = (c: Cache) =>
  Object.values(c.entries).filter((e) => isPending(e.phase)).length;

const dropCell = (c: Cache, key: string): Cache => {
  const entry = c.entries[key];
  if (!entry) return c;
  return {
    entries: without(c.entries, key),
    routes: without(c.routes, entry.explainId),
  };
};

// Evict oldest *settled* cells until under `cap`; never touches in-flight ones.
const evict = (c: Cache, cap: number): Cache => {
  const keys = Object.keys(c.entries);
  if (keys.length < cap) return c;
  const oldestSettledFirst = keys
    .filter((k) => !isPending(c.entries[k].phase))
    .sort((a, b) => c.entries[a].startedAt - c.entries[b].startedAt);
  let next = c;
  let count = keys.length;
  for (const key of oldestSettledFirst) {
    if (count < cap) break;
    next = dropCell(next, key);
    count -= 1;
  }
  return next;
};

// Insert a cell's entry (evicting to the cap first). `routed` registers the
// explainId→cell route for an entry that will receive streamed chunks.
const putCell = (
  c: Cache,
  key: string,
  entry: ExplainEntry,
  routed: boolean,
): Cache => {
  const trimmed = evict(c, MAX_CACHED);
  return {
    entries: { ...trimmed.entries, [key]: entry },
    routes: routed
      ? { ...trimmed.routes, [entry.explainId]: key }
      : trimmed.routes,
  };
};

// Apply a transition to the cell a stream routes into. No-op on an unknown or
// retired explainId. `retire` drops the route afterwards (terminal events only).
const patchStream = (
  c: Cache,
  explainId: string,
  next: (entry: ExplainEntry) => ExplainEntry,
  retire = false,
): Cache => {
  const key = c.routes[explainId];
  if (!key) return c;
  const routes = retire ? without(c.routes, explainId) : c.routes;
  const entry = c.entries[key];
  if (!entry) return { entries: c.entries, routes };
  return { entries: { ...c.entries, [key]: next(entry) }, routes };
};

// ─────────────────────────────────────────────────────────────────────────────
// Store
// ─────────────────────────────────────────────────────────────────────────────

type ExplainState = Cache & {
  capabilities: string[];
  // The console's bridge protocol version from its `console:ready` handshake;
  // null until it arrives. Gates the buttons (see useCanExplain).
  consoleBridgeVersion: number | null;
  // Whether the assistant pod is live (mirrored from useAssistantBackend).
  ready: boolean;
  emit: ConsoleEmit | null;

  setReady: (ready: boolean) => void;
  setEmit: (emit: ConsoleEmit) => void;
  clearEmit: (emit: ConsoleEmit) => void;

  // True when there's now something to show (a fresh stream dispatched, or a
  // cached/in-flight result reused); false when throttled by MAX_IN_FLIGHT.
  explain: (target: ExplainTarget) => boolean;
  retry: (target: ExplainTarget) => void;
  cancel: (target: ExplainTarget) => void;
  continueChat: (target: ExplainTarget, question: string) => void;

  onConsoleReady: (data: SidebarEventMap["console:ready"]) => void;
  onChunk: (data: SidebarEventMap["explain:chunk"]) => void;
  onDone: (data: SidebarEventMap["explain:done"]) => void;
  onError: (data: SidebarEventMap["explain:error"]) => void;
};

const useExplainStore = create<ExplainState>((set, get) => {
  const mutate = (fn: (cache: Cache) => Cache) => set((s) => fn(s));

  // The routed cell for a stream that hasn't produced a token yet — what the
  // watchdog acts on. Undefined once a chunk arrives or the route is retired.
  const stalledCell = (explainId: string) => {
    const entry = cellOf(get(), explainId);
    return entry?.explainId === explainId && entry.firstTokenAt === undefined
      ? entry
      : undefined;
  };

  // Settle a stream's cell into an error (telemetry + copy). `retire` drops the
  // route for terminal failures (timeout/pod-loss); console errors keep it so a
  // retry's recovery chunk can still resurrect the cell via `withChunk`.
  const failCell = (
    explainId: string,
    code: string | undefined,
    message: string,
    retire: boolean,
  ) => {
    const entry = cellOf(get(), explainId);
    if (!entry) return;
    // `message` is the resolved display copy: for known codes it's the static
    // ERROR_COPY line, for unknown codes it's the raw upstream error — the
    // diagnostic detail dashboards need. Truncated: upstream text is unbounded.
    trackEvent(OpikEvent.EXPLAIN_ERRORED, {
      kind: entry.kind,
      code: code ?? "unknown",
      message: message.slice(0, 300),
    });
    mutate((c) =>
      patchStream(
        c,
        explainId,
        (e) => ({ ...e, phase: "error", error: message, code }),
        retire,
      ),
    );
  };

  const watchdog = createStreamWatchdog({
    wakingMs: WAKING_MS,
    timeoutMs: TIMEOUT_MS,
    onWaking: (explainId) => {
      if (stalledCell(explainId)?.phase === "loading") {
        mutate((c) =>
          patchStream(c, explainId, (e) => ({ ...e, phase: "waking" })),
        );
      }
    },
    onTimeout: (explainId) => {
      const entry = stalledCell(explainId);
      if (entry && isPending(entry.phase)) {
        get().emit?.("explain:cancel", { explainId });
        failCell(explainId, "timeout", ERROR_COPY.timeout, true);
      }
    },
  });

  // Fail every in-flight cell — used when the pod/bridge goes away so open
  // popovers can't hang on "Thinking…/waking" forever.
  const failAllInFlight = (code: ErrorCode) => {
    Object.values(get().entries).forEach((entry) => {
      if (isPending(entry.phase)) {
        watchdog.disarm(entry.explainId);
        failCell(entry.explainId, code, ERROR_COPY[code], true);
      }
    });
  };

  return {
    entries: {},
    routes: {},
    capabilities: [],
    consoleBridgeVersion: null,
    ready: false,
    emit: null,

    // A pod that goes unready can't finish an in-flight stream — fail them so the
    // popover offers a retry instead of a permanent "Thinking…".
    setReady: (ready) => {
      if (!ready) failAllInFlight("unavailable");
      set({ ready });
    },
    setEmit: (emit) => set({ emit }),
    // Ownership-guarded (mirrors window.opikBridge): a stale sidebar unmount
    // can't drop a newer instance's channel.
    clearEmit: (emit) => {
      if (get().emit !== emit) return;
      failAllInFlight("unavailable");
      set({
        emit: null,
        capabilities: [],
        consoleBridgeVersion: null,
        ready: false,
      });
    },

    explain: (target) => {
      const key = cellKey(target);
      const cached = get().entries[key];
      if (cached && cached.phase !== "error") return true; // reuse cache / in-flight

      // Re-explaining an errored cell (reopening the popover calls explain(),
      // not retry()): drop the stale cell first so its lingering route — kept by
      // onError, retire=false, for a console same-id retry — is removed. Without
      // this the fresh stream's explainId would alias the same cell as the
      // abandoned one, so a late chunk/done/error from the old stream would
      // corrupt, truncate, or wrongly-error the new answer the user is reading
      // (and the orphaned route would leak). Mirrors retry()'s dropCell.
      if (cached) {
        watchdog.disarm(cached.explainId);
        mutate((c) => dropCell(c, key));
      }

      // At capacity: surface a clear, retryable error rather than a stuck
      // "Thinking…". No stream is dispatched, so no route is tracked.
      if (inFlightCount(get()) >= MAX_IN_FLIGHT) {
        mutate((c) =>
          putCell(c, key, erroredEntry(target, AT_CAPACITY), false),
        );
        return false;
      }

      const entry = startEntry(target);
      mutate((c) => putCell(c, key, entry, true));
      get().emit?.("explain:run", { explainId: entry.explainId, target });
      watchdog.arm(entry.explainId);
      return true;
    },

    // Stop any prior stream for the cell, then dispatch a fresh one. Today Retry
    // only shows after an error, but guarding the pending case keeps it safe if
    // ever invoked mid-stream.
    retry: (target) => {
      const key = cellKey(target);
      const prev = get().entries[key];
      if (prev) {
        watchdog.disarm(prev.explainId);
        if (isPending(prev.phase)) {
          get().emit?.("explain:cancel", { explainId: prev.explainId });
        }
      }
      mutate((c) => dropCell(c, key));
      get().explain(target);
    },

    // Stop an in-flight stream and reset the cell so reopening starts fresh.
    // No-op on a settled cell — cached done/error entries stay put. (Without the
    // reset, `explain()` would short-circuit on the stale pending entry and the
    // popover would pulse "Thinking…" forever.)
    cancel: (target) => {
      const entry = get().entries[cellKey(target)];
      if (!entry || !isPending(entry.phase)) return;
      watchdog.disarm(entry.explainId);
      get().emit?.("explain:cancel", { explainId: entry.explainId });
      mutate((c) => dropCell(c, cellKey(target)));
    },

    continueChat: (target, question) => {
      const entry = get().entries[cellKey(target)];
      // Offered as soon as any text streamed (done OR still loading); refused on
      // empty / errored cells.
      if (!entry || entry.phase === "error" || entry.text.length === 0) return;
      // Mid-stream: the chat takes over in the sidebar, so stop the (paid) cell
      // stream and freeze the partial text as the cached answer. Freezing to
      // "done" + retiring the route also makes the popover-close cancel() a
      // no-op, so the stream isn't cancelled twice.
      if (isPending(entry.phase)) {
        watchdog.disarm(entry.explainId);
        get().emit?.("explain:cancel", { explainId: entry.explainId });
        mutate((c) =>
          patchStream(
            c,
            entry.explainId,
            (e) => ({ ...e, phase: "done" }),
            true,
          ),
        );
      }
      get().emit?.("chat:continue", { question, answer: entry.text, target });
    },

    onConsoleReady: ({ bridgeVersion, capabilities }) =>
      set({ capabilities, consoleBridgeVersion: bridgeVersion }),

    onChunk: ({ explainId, delta }) => {
      // First chunk proves the pod is alive → retire the watchdog (idempotent).
      watchdog.disarm(explainId);
      mutate((c) => patchStream(c, explainId, (e) => withChunk(e, delta)));
    },

    // Completion telemetry fires from the store (the popover's lifecycle doesn't
    // match the stream's). A `done` for a cell still in `error` means the stream
    // ended without ever recovering — keep the error rather than blank it into an
    // empty "done", and don't count a completion. `done` is terminal either way,
    // so the route is retired.
    onDone: ({ explainId }) => {
      watchdog.disarm(explainId);
      const entry = cellOf(get(), explainId);
      if (entry && entry.phase !== "error") {
        trackEvent(OpikEvent.EXPLAIN_COMPLETED, {
          kind: entry.kind,
          ttft_ms: entry.firstTokenAt
            ? Math.round(entry.firstTokenAt - entry.startedAt)
            : null,
        });
      }
      mutate((c) =>
        patchStream(
          c,
          explainId,
          (e) => (e.phase === "error" ? e : { ...e, phase: "done" }),
          true,
        ),
      );
    },

    // Console errors are recoverable: a retry of a transient failure can still
    // stream chunks under the same explainId afterwards, so keep the route
    // (retire=false) and let `onChunk` resurrect the cell.
    onError: ({ explainId, message, code }) => {
      watchdog.disarm(explainId);
      failCell(explainId, code, errorMessage(code, message), false);
    },
  };
});

/**
 * Forward a shell→host bridge event into the explain store. Returns false when
 * `event` isn't an explain event, so the bridge falls through to its default
 * handling. Owns the explain event shapes (and the casts at the untyped bridge
 * boundary) rather than leaking them into the sidebar.
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

// Single gate read by the (per-row) button — no per-row backend hooks. Show
// Explain only when the whole Ollie integration is usable: a live bridge + pod,
// the console advertised the "explain" capability (its remote on/off), and its
// bridge is at least as new as ours so the events we emit are understood (newer
// consoles stay compatible, hence `>=`).
export const useCanExplain = () =>
  useExplainStore(
    (s) =>
      s.emit !== null &&
      s.ready &&
      s.capabilities.includes("explain") &&
      s.consoleBridgeVersion !== null &&
      s.consoleBridgeVersion >= BRIDGE_PROTOCOL_VERSION,
  );

export default useExplainStore;
