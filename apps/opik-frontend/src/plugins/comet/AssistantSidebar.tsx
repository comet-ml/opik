import React, {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import { useParams, useRouter } from "@tanstack/react-router";
import {
  AssistantSurfaceVariant,
  BridgeContext,
  BridgeSurface,
  RunnerBridgeState,
  SidebarEventMap,
} from "@/types/assistant-sidebar";
import { useActiveWorkspaceName } from "@/store/AppStore";
import { useTheme } from "@/contexts/theme-provider";
import { useToast } from "@/ui/use-toast";
import useWorkspace from "@/plugins/comet/useWorkspace";
import useAssistantBackend from "@/plugins/comet/useAssistantBackend";
import useProjectById from "@/api/projects/useProjectById";
import useProjectOnboardingStats from "@/hooks/useProjectOnboardingStats";
import useRunnerBridgeSync from "@/hooks/useRunnerBridgeSync";
import { BASE_API_URL } from "@/api/api";
import AssistantErrorState from "@/plugins/comet/AssistantErrorState";
import OllieLoader from "@/plugins/comet/OllieLoader";
import { IS_ASSISTANT_DEV } from "@/plugins/comet/constants/assistant";
import useAssistantManifest from "@/plugins/comet/useAssistantManifest";
import {
  ASSISTANT_SIDEBAR_COLLAPSED_WIDTH,
  getStoredAssistantSidebarWidth,
  isAssistantSidebarOpen,
  setAssistantSidebarOpen,
} from "@/constants/assistantSidebar";
import useExplainStore from "@/plugins/comet/explain/explainStore";
import {
  createBridge,
  createHostListeners,
  emitHostEvent,
  useLatestRef,
  useRegisterExplainEmitter,
  type HostListeners,
} from "@/plugins/comet/assistantBridge";

interface AssistantSidebarLoaderProps {
  error: string | null;
  onWidthChange: (width: number) => void;
  onRetry?: () => void;
  retryCount?: number;
  surface: BridgeSurface;
}

const AssistantSidebarLoader: React.FC<AssistantSidebarLoaderProps> = ({
  error,
  onWidthChange,
  onRetry,
  retryCount = 0,
  surface,
}) => {
  const [isOpen, setIsOpen] = useState(isAssistantSidebarOpen);
  const initialWidth = useRef(
    isAssistantSidebarOpen()
      ? getStoredAssistantSidebarWidth()
      : ASSISTANT_SIDEBAR_COLLAPSED_WIDTH,
  );

  useEffect(() => {
    onWidthChange(initialWidth.current);
  }, [onWidthChange]);

  const handleToggle = useCallback(() => {
    setIsOpen((prev) => {
      const next = !prev;
      setAssistantSidebarOpen(next);
      onWidthChange(
        next
          ? getStoredAssistantSidebarWidth()
          : ASSISTANT_SIDEBAR_COLLAPSED_WIDTH,
      );
      return next;
    });
  }, [onWidthChange]);

  let variant: AssistantSurfaceVariant;
  if (surface === "page") {
    variant = "page";
  } else if (!isOpen) {
    variant = "collapsed";
  } else {
    variant = "sidebar";
  }

  if (error) {
    return (
      <AssistantErrorState
        variant={variant}
        onRetry={onRetry}
        onToggle={handleToggle}
        retryCount={retryCount}
      />
    );
  }

  return <OllieLoader variant={variant} />;
};

const stopPropagation = (e: Event) => e.stopPropagation();

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
  const meta = useAssistantManifest(probeUrl);
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
   *
   * The workspace is injected via a `$workspaceName` template param rather
   * than string concatenation. TanStack Router strips the basepath from `to`
   * with an unbounded `^basepath` regex (see @tanstack/react-router path.js
   * `resolvePath`/`removeBasepath`), so a literal `to: "/opik-demos/..."`
   * with basepath `/opik` would be mangled to `/opik/-demos/...`. Keeping
   * the workspace as a param defers substitution until after the strip runs.
   */
  const navigateRef = useLatestRef(
    (path: string, search?: Record<string, unknown>) => {
      const ws = contextRef.current.workspaceName;
      if (ws) {
        router.navigate({
          to: `/$workspaceName${path}`,
          params: { workspaceName: ws },
          search,
        });
      } else {
        router.navigate({ to: path, search });
      }
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

  useRegisterExplainEmitter(listenersRef);

  // Mirror pod readiness into the explain store so the (per-row) Explain
  // buttons gate on one shared value rather than each calling the backend hook.
  // No unmount reset here on purpose: a surface switch (sidebar↔page) mounts the
  // new instance — which sets ready=true — BEFORE the old one's cleanup runs (the
  // same ordering that forces the ownership guards on window.opikBridge/clearEmit
  // above). An unconditional setReady(false) on unmount would clobber the live
  // instance and, since its effect dep already settled, never restore it — every
  // Explain button would silently vanish until reload. Teardown is instead owned
  // by the ownership-guarded clearEmit, which resets ready only for the instance
  // that still owns the bridge.
  useEffect(() => {
    useExplainStore.getState().setReady(isBackendReady);
  }, [isBackendReady]);

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
    return (
      <AssistantSidebarLoader
        error={error}
        onWidthChange={onWidthChange}
        onRetry={retry}
        retryCount={retryCount}
        surface={surface}
      />
    );
  }

  return (
    <iframe
      ref={setIframeRef}
      data-testid="ollie-assistant-iframe"
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
