import React, {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import { useQuery } from "@tanstack/react-query";
import { useParams, useRouter } from "@tanstack/react-router";
import {
  AssistantSidebarBridge,
  BridgeContext,
  BridgeSurface,
  HostEventMap,
  RunnerBridgeState,
  SidebarEventMap,
} from "@/types/assistant-sidebar";
import { useActiveWorkspaceName } from "@/store/AppStore";
import { useTheme } from "@/contexts/theme-provider";
import { useToast } from "@/ui/use-toast";
import useWorkspace from "@/plugins/comet/useWorkspace";
import useAssistantBackend from "@/plugins/comet/useAssistantBackend";
import type { AssistantBackendPhase } from "@/plugins/comet/useAssistantBackend";
import useProjectById from "@/api/projects/useProjectById";
import useProjectOnboardingStats from "@/hooks/useProjectOnboardingStats";
import useRunnerBridgeSync from "@/hooks/useRunnerBridgeSync";
import { BASE_API_URL } from "@/api/api";
import { Spinner } from "@/ui/spinner";
import AssistantErrorState from "@/plugins/comet/AssistantErrorState";
import {
  ASSISTANT_DEV_BASE_URL,
  IS_ASSISTANT_DEV,
} from "@/plugins/comet/constants/assistant";

const BRIDGE_PROTOCOL_VERSION = 1;

const LOADER_DEFAULT_WIDTH = 400;
const LOADER_COLLAPSED_WIDTH = 33;

// Pod may serve /console/manifest.json before /health/ready flips — retry
// with backoff so transient 404/503 during warmup don't permanently fail.
// Budget (~140s) exceeds the 2 min health-poll timeout so manifest doesn't
// give up before health polling does.
const MANIFEST_RETRY_COUNT = 30;
const MANIFEST_RETRY_BASE_DELAY_MS = 500;
const MANIFEST_RETRY_MAX_DELAY_MS = 5000;

function getStoredSidebarWidth(): number {
  try {
    const parsed = parseInt(
      localStorage.getItem("assistant-sidebar-width") ?? "",
      10,
    );
    if (parsed > 0) return parsed;
  } catch {
    /* localStorage unavailable */
  }
  return LOADER_DEFAULT_WIDTH;
}

function getStoredSidebarOpen(): boolean {
  try {
    const stored = localStorage.getItem("assistant-sidebar-open");
    return stored === null ? true : stored === "true";
  } catch {
    return true;
  }
}

const PHASE_MESSAGES: Partial<
  Record<AssistantBackendPhase | "manifest", string>
> = {
  compute: "Starting assistant\u2026",
  health: "Connecting\u2026",
  manifest: "Loading interface\u2026",
};

interface AssistantSidebarLoaderProps {
  phase: AssistantBackendPhase | "manifest";
  error: string | null;
  onWidthChange: (width: number) => void;
  onRetry?: () => void;
  retryCount?: number;
}

const AssistantSidebarLoader: React.FC<AssistantSidebarLoaderProps> = ({
  phase,
  error,
  onWidthChange,
  onRetry,
  retryCount = 0,
}) => {
  const [isOpen, setIsOpen] = useState(getStoredSidebarOpen);
  const initialWidth = useRef(
    getStoredSidebarOpen() ? getStoredSidebarWidth() : LOADER_COLLAPSED_WIDTH,
  );

  useEffect(() => {
    onWidthChange(initialWidth.current);
  }, [onWidthChange]);

  const handleToggle = useCallback(() => {
    setIsOpen((prev) => {
      const next = !prev;
      localStorage.setItem("assistant-sidebar-open", String(next));
      onWidthChange(next ? getStoredSidebarWidth() : LOADER_COLLAPSED_WIDTH);
      return next;
    });
  }, [onWidthChange]);

  const collapsed = !isOpen;

  if (error) {
    return (
      <AssistantErrorState
        collapsed={collapsed}
        onRetry={onRetry}
        onToggle={handleToggle}
        retryCount={retryCount}
      />
    );
  }

  const message = PHASE_MESSAGES[phase] ?? "Loading\u2026";

  return (
    <div className="relative size-full border-l">
      <div className="absolute inset-0 animate-pulse bg-muted" />
      <div className="relative flex size-full items-center justify-center">
        <Spinner size={collapsed ? "xs" : "small"} />
        {!collapsed && (
          <span className="ml-2 text-sm text-light-slate">{message}</span>
        )}
      </div>
    </div>
  );
};

const stopPropagation = (e: Event) => e.stopPropagation();

/** Keeps a ref whose `.current` always points to the latest value. */
function useLatestRef<T>(value: T): React.MutableRefObject<T> {
  const ref = useRef(value);
  ref.current = value;
  return ref;
}

interface AssistantManifest {
  js: string;
  css?: string;
  shell: string;
  ver: string;
}

type HostListeners = {
  [K in keyof HostEventMap]: Set<(data: HostEventMap[K]) => void>;
};

function createHostListeners(): HostListeners {
  return {
    "context:changed": new Set(),
    "visibility:changed": new Set(),
    "runner:state-changed": new Set(),
  };
}

interface BridgeRefs {
  navigate: React.MutableRefObject<
    (path: string, search?: Record<string, unknown>) => void
  >;
  onWidthChange: React.MutableRefObject<(width: number) => void>;
  onNotification: React.MutableRefObject<
    (data: SidebarEventMap["notification"]) => void
  >;
  onRequestVisibility: React.MutableRefObject<(open: boolean) => void>;
  onRequestPair: React.MutableRefObject<
    (data: SidebarEventMap["runner:request-pair"]) => void
  >;
  context: React.MutableRefObject<BridgeContext>;
  listeners: React.MutableRefObject<HostListeners>;
  lastRunnerState: React.MutableRefObject<RunnerBridgeState | null>;
}

const createBridge = (refs: BridgeRefs): AssistantSidebarBridge => ({
  version: BRIDGE_PROTOCOL_VERSION,
  getContext: () => refs.context.current,
  subscribe: (event, callback) => {
    const set = refs.listeners.current[event as keyof HostEventMap] as
      | Set<typeof callback>
      | undefined;
    if (!set) return () => {};
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
        if (IS_ASSISTANT_DEV) {
          console.warn(
            `[AssistantBridge] Unhandled sidebar event: "${event}"`,
            data,
          );
        }
    }
  },
});

/** Emit a host event to all subscribed sidebar listeners. */
function emitHostEvent<E extends keyof HostEventMap>(
  listenersRef: React.MutableRefObject<HostListeners>,
  event: E,
  data: HostEventMap[E],
) {
  for (const listener of listenersRef.current[event]) {
    (listener as (d: HostEventMap[E]) => void)(data);
  }
}

function useBridgeContext(
  assistantBackendUrl: string,
  surface: BridgeSurface,
): BridgeContext {
  const workspaceName = useActiveWorkspaceName();
  const { themeMode } = useTheme();
  const workspace = useWorkspace();

  const { projectId } = useParams({ strict: false }) as {
    projectId?: string;
  };
  const { data: project } = useProjectById(
    { projectId: projectId! },
    { enabled: !!projectId },
  );

  const workspaceId = workspace?.workspaceId ?? "";
  const projectName = project?.name ?? null;
  const resolvedProjectId = projectId ?? null;

  const organizationId = workspace?.organizationId ?? null;
  const projectStats = useProjectOnboardingStats(resolvedProjectId);

  return useMemo<BridgeContext>(
    () => ({
      workspaceId,
      workspaceName,
      organizationId,
      projectId: resolvedProjectId,
      projectName,
      baseApiUrl: BASE_API_URL,
      assistantBackendUrl,
      theme: themeMode,
      surface,
      projectStats,
    }),
    [
      workspaceId,
      workspaceName,
      organizationId,
      resolvedProjectId,
      projectName,
      assistantBackendUrl,
      themeMode,
      surface,
      projectStats,
    ],
  );
}

interface AssistantMeta {
  scriptUrl: string;
  cssUrl?: string;
  shellUrl: string;
  version: string;
}

function resolveManifestUrl(backendUrl: string | null): string | null {
  if (ASSISTANT_DEV_BASE_URL) return `${ASSISTANT_DEV_BASE_URL}/manifest.json`;
  if (backendUrl) return `${backendUrl}/console/manifest.json`;
  return null;
}

const DEV_META: AssistantMeta = {
  scriptUrl: "/assistant/assistant.js",
  cssUrl: "/assistant/assistant.css",
  shellUrl: "/assistant/shell",
  version: "dev",
};

function useAssistantMeta(backendUrl: string | null): AssistantMeta | null {
  const manifestUrl = resolveManifestUrl(backendUrl);

  const manifestBase = manifestUrl
    ? manifestUrl.substring(0, manifestUrl.lastIndexOf("/"))
    : null;

  const { data } = useQuery<AssistantMeta>({
    queryKey: ["assistant-manifest", manifestUrl],
    queryFn: async () => {
      const res = await fetch(manifestUrl!);
      if (!res.ok) throw new Error(`manifest ${res.status}`);
      const manifest: AssistantManifest = await res.json();
      return {
        scriptUrl: `${manifestBase}/${manifest.js}`,
        cssUrl: manifest.css ? `${manifestBase}/${manifest.css}` : undefined,
        shellUrl: `/assistant/${manifest.shell}`,
        version: manifest.ver,
      };
    },
    enabled: !IS_ASSISTANT_DEV && !!manifestUrl,
    staleTime: Infinity,
    retry: MANIFEST_RETRY_COUNT,
    retryDelay: (attempt) =>
      Math.min(
        MANIFEST_RETRY_BASE_DELAY_MS * 2 ** attempt,
        MANIFEST_RETRY_MAX_DELAY_MS,
      ),
  });

  if (IS_ASSISTANT_DEV) return DEV_META;

  return data ?? null;
}

interface AssistantSidebarProps {
  surface?: BridgeSurface;
  onWidthChange: (width: number) => void;
}

const AssistantSidebar: React.FC<AssistantSidebarProps> = ({
  surface = "sidebar",
  onWidthChange,
}) => {
  const {
    backendUrl,
    probeUrl,
    isReady: isBackendReady,
    error,
    phase,
    retry,
    retryCount,
  } = useAssistantBackend();
  const meta = useAssistantMeta(probeUrl);
  const context = useBridgeContext(backendUrl ?? "", surface);
  const router = useRouter();

  const { toast } = useToast();

  // Warm DNS/TCP/TLS to the pod origin while health polling is in flight.
  // Attributes must be set BEFORE appendChild — browsers evaluate the hint at
  // insertion time, and late crossorigin changes may not upgrade the handshake.
  useEffect(() => {
    if (!probeUrl || IS_ASSISTANT_DEV) return;
    const origin = new URL(probeUrl).origin;
    const link = document.createElement("link");
    link.rel = "preconnect";
    link.href = origin;
    link.setAttribute("crossorigin", "use-credentials");
    document.head.appendChild(link);
    return () => {
      link.remove();
    };
  }, [probeUrl]);

  const contextRef = useLatestRef(context);
  const onWidthChangeRef = useLatestRef(onWidthChange);
  const listenersRef = useRef<HostListeners>(createHostListeners());
  const lastRunnerStateRef = useRef<RunnerBridgeState | null>(null);

  const { handleRequestPair } = useRunnerBridgeSync({
    projectId: context.projectId,
    onStateChanged: (state) => {
      lastRunnerStateRef.current = state;
      emitHostEvent(listenersRef, "runner:state-changed", state);
    },
  });

  const onRequestPairRef = useLatestRef(handleRequestPair);

  const onNotificationRef = useLatestRef(
    (data: SidebarEventMap["notification"]) => {
      toast({
        title: data.message,
        variant: data.type === "error" ? "destructive" : "default",
      });
    },
  );

  // Best-effort: only effective if the sidebar has subscribed to visibility:changed
  const onRequestVisibilityRef = useLatestRef((open: boolean) => {
    emitHostEvent(listenersRef, "visibility:changed", { isOpen: open });
  });

  /**
   * Forwards a sidebar navigation request to TanStack Router. `search` is
   * typed `Record<string, unknown>` because the bridge is route-agnostic and
   * no runtime narrowing is possible here — the producer (ollie-assist) is
   * responsible for supplying values that match the destination route's
   * search schema. Structured values are single-stringified by the router so
   * `use-query-params`' `JsonParam` round-trips correctly.
   */
  const navigateRef = useLatestRef(
    (path: string, search?: Record<string, unknown>) => {
      const ws = contextRef.current.workspaceName;
      const fullPath = ws ? `/${ws}${path}` : path;
      router.navigate({ to: fullPath, search });
    },
  );

  const bridgeRef = useRef(
    createBridge({
      navigate: navigateRef,
      onWidthChange: onWidthChangeRef,
      onNotification: onNotificationRef,
      onRequestVisibility: onRequestVisibilityRef,
      onRequestPair: onRequestPairRef,
      context: contextRef,
      listeners: listenersRef,
      lastRunnerState: lastRunnerStateRef,
    }),
  );

  // Expose bridge and meta on window for iframe access.
  // Guard the cleanup: when another AssistantSidebar instance mounts (e.g.
  // switching between sidebar and page surface), it overwrites these globals
  // with its own bridge/meta. A later unmount of the previous instance must
  // NOT clobber the new values — only clean up when our bridge is still the
  // active one. Meta cleanup is tied to bridge ownership because both serve
  // the same iframe and meta is a shared React Query cache reference that
  // cannot be compared with === across instances.
  useEffect(() => {
    const bridge = bridgeRef.current;
    window.opikBridge = bridge;
    if (meta) {
      window.__opikAssistantMeta__ = meta;
    }
    return () => {
      if (window.opikBridge === bridge) {
        delete window.opikBridge;
        delete window.__opikAssistantMeta__;
      }
    };
  }, [meta]);

  // Emit context changes to sidebar listeners
  useEffect(() => {
    emitHostEvent(listenersRef, "context:changed", context);
  }, [context]);

  // Notify sidebar of visibility on mount/unmount
  useEffect(() => {
    emitHostEvent(listenersRef, "visibility:changed", { isOpen: true });
    return () => {
      emitHostEvent(listenersRef, "visibility:changed", { isOpen: false });
    };
  }, []);

  // Prevent host FocusScope from trapping focus when the iframe gains focus.
  // Radix's FocusScope yanks focus back when it leaves a dialog — stopping
  // propagation on the iframe's focusin keeps it away from document-level listeners.
  const iframeRef = useRef<HTMLIFrameElement | null>(null);
  const setIframeRef = useCallback((node: HTMLIFrameElement | null) => {
    const prev = iframeRef.current;
    if (prev) {
      prev.removeEventListener("focusin", stopPropagation);
    }
    iframeRef.current = node;
    if (node) {
      node.addEventListener("focusin", stopPropagation);
    }
  }, []);

  // On the page surface, the iframe fills the whole main area, so clicks
  // inside Ollie never bubble to the parent document. Detect the resulting
  // window blur and synthesize the events Radix's DismissableLayer is
  // waiting for, so any open popover/select/dropdown closes. Different
  // Radix versions listen on `pointerdown` and/or `mousedown`; dispatch both.
  useEffect(() => {
    if (surface !== "page") return;
    const handleBlur = () => {
      // Only dismiss when focus actually moved INTO our iframe (skip
      // tab/window switches).
      if (document.activeElement !== iframeRef.current) return;
      document.dispatchEvent(
        new PointerEvent("pointerdown", { bubbles: true, composed: true }),
      );
      document.dispatchEvent(
        new MouseEvent("mousedown", { bubbles: true, composed: true }),
      );
    };
    window.addEventListener("blur", handleBlur);
    return () => window.removeEventListener("blur", handleBlur);
  }, [surface]);

  if (!meta || !isBackendReady) {
    if (phase === "disabled") return null;
    const effectivePhase = isBackendReady && !meta ? "manifest" : phase;
    return (
      <AssistantSidebarLoader
        phase={effectivePhase}
        error={error}
        onWidthChange={onWidthChange}
        onRetry={retry}
        retryCount={retryCount}
      />
    );
  }

  return (
    <iframe
      ref={setIframeRef}
      src={meta.shellUrl}
      className="size-full border-none"
      // Radix's DismissableLayer sets pointer-events:none on the body when a
      // modal dialog is open — this keeps the iframe clickable.
      style={{ pointerEvents: "auto" }}
      title="Assistant"
      allow="clipboard-write"
    />
  );
};

export default AssistantSidebar;
