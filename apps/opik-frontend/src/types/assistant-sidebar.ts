import { RunnerConnectionStatus } from "@/types/agent-sandbox";
import { CELL_HORIZONTAL_ALIGNMENT } from "@/types/shared";

export type BridgeTheme = "light" | "dark";
export type BridgeSurface = "sidebar" | "page";
export type AssistantSurfaceVariant = "page" | "sidebar" | "collapsed";
export type NotificationType = "success" | "error" | "info";

export interface ProjectStats {
  traceCount: number;
  experimentCount: number;
  optimizationCount: number;
  blueprintVersionCount: number;
}

export interface BridgeContext {
  workspaceId: string;
  workspaceName: string;
  organizationId?: string | null;
  projectId: string | null;
  projectName: string | null;
  baseApiUrl: string;
  assistantBackendUrl: string;
  theme: BridgeTheme;
  surface: BridgeSurface;
  projectStats?: ProjectStats;
}

export interface RunnerBridgeState {
  projectId: string;
  status: RunnerConnectionStatus;
  runnerId: string | null;
}

/** The cell kinds the Explain feature understands, one per (entity, metric)
 * pair surfaced in the Logs tables. Traces & Spans expose error/duration/cost;
 * Threads expose duration/cost only (no per-thread error). The console's
 * ExplainRunner must understand every kind here (coordinate when adding). */
export type ExplainKind =
  | "trace.error"
  | "trace.cost"
  | "trace.duration"
  | "span.error"
  | "span.duration"
  | "span.cost"
  | "thread.duration"
  | "thread.cost";

/**
 * The cell data the host already has on the row, handed verbatim to the
 * console's ExplainRunner (no extra fetch). `payload` is kind-specific and
 * shaped by the FE kind registry; the bridge stays transport-neutral and does
 * not validate it.
 *
 * ⚠️ This type and the 7 explain/chat/console events below MUST stay
 * byte-identical with `ollie-console/src/bridge.ts`. The runtime bridge is
 * untyped, so a name/shape skew silently no-ops (a permanent thinking-pulse
 * bug). VER-4's compile-time assertions guard the event keys; keep this shape
 * in sync by hand until a shared `@comet/ollie-bridge-types` package exists.
 */
export interface ExplainTarget {
  kind: ExplainKind;
  entityId: string;
  projectId: string;
  payload: Record<string, unknown>;
}

/** Props for the plugin-provided ExplainButton, surfaced via PluginsStore. */
export interface ExplainButtonProps {
  target: ExplainTarget;
  // The host cell's resolved horizontal alignment. The owl anchors itself on the
  // side opposite the value (so it never covers it) and grows away from it:
  // left-aligned cells → owl on the right, grows left; right-aligned cells → owl
  // on the left, grows right. Defaults to `start` when omitted.
  align?: CELL_HORIZONTAL_ALIGNMENT;
}

/**
 * Host↔shell bridge protocol version.
 *
 * 2 = explain/chat events added (`explain:run`/`explain:cancel`/`chat:continue`
 * + `console:ready`/`explain:chunk`/`explain:done`/`explain:error`).
 *
 * ⚠️ MUST move in lockstep with `ollie-console/src/bridge.ts`; the console's
 * VER-4 assertions guard the event-shape contract. Bump here AND there together
 * whenever a bridge event is added/removed/reshaped.
 */
export const BRIDGE_PROTOCOL_VERSION = 2;

/** Host → Sidebar events */
export interface HostEventMap {
  "context:changed": BridgeContext;
  "visibility:changed": { isOpen: boolean };
  "runner:state-changed": RunnerBridgeState;
  "conversation:start": { message: string };
  // Explain (host → shell). `explainId` correlates concurrent explains over
  // the single bridge. `chat:continue` carries the verbatim Q&A already shown
  // in the popover so the console seeds one consistent session.
  "explain:run": { explainId: string; target: ExplainTarget };
  "explain:cancel": { explainId: string };
  "chat:continue": { question: string; answer: string; target: ExplainTarget };
}

/** Sidebar → Host events */
export interface SidebarEventMap {
  // `search` values are `unknown` so structured data (filter arrays, sort
  // specs) can flow through to TanStack Router as real objects. Passing
  // them as pre-serialized JSON strings triggers TanStack's double-encode
  // path in stringifySearchWith and breaks use-query-params readers.
  navigate: { path: string; search?: Record<string, unknown> };
  notification: { message: string; type: NotificationType };
  "sidebar:resized": { width: number };
  "sidebar:request-close": Record<string, never>;
  "sidebar:request-open": Record<string, never>;
  "runner:request-pair": { projectId: string };
  // Explain (shell → host). `console:ready` is the mount handshake whose
  // `capabilities` gate the Explain buttons (old console builds without
  // "explain" degrade to no-buttons). `explain:done` carries no sessionId —
  // there is no session; Continue seeds one later from the streamed text.
  "console:ready": { bridgeVersion: number; capabilities: string[] };
  "explain:chunk": { explainId: string; delta: string };
  "explain:done": { explainId: string };
  // `code` is an optional, machine-readable reason (e.g. "unavailable",
  // "rate_limited") the host maps to contextual copy; falls back to `message`.
  // Additive/optional — mirror in `ollie-console/src/bridge.ts` when the console
  // starts sending it.
  "explain:error": { explainId: string; message: string; code?: string };
}

export interface AssistantSidebarBridge {
  version: number;
  getContext(): BridgeContext;
  subscribe<E extends keyof HostEventMap>(
    event: E,
    callback: (data: HostEventMap[E]) => void,
  ): () => void;
  emit<E extends keyof SidebarEventMap>(
    event: E,
    data: SidebarEventMap[E],
  ): void;
  startConversation(message: string): void;
}

declare global {
  interface Window {
    AssistantConsole?: {
      mount: (element: HTMLElement, bridge: AssistantSidebarBridge) => void;
      unmount: (element: HTMLElement) => void;
    };
    opikBridge?: AssistantSidebarBridge;
    __opikAssistantMeta__?: {
      scriptUrl: string;
      cssUrl?: string;
      shellUrl: string;
      version: string;
    };
  }
}
