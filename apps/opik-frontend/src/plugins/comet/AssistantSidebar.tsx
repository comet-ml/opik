import React, { useCallback, useEffect, useMemo, useRef } from "react";
import { useQuery } from "@tanstack/react-query";
import { useParams, useRouter } from "@tanstack/react-router";
import {
  AssistantSidebarBridge,
  BridgeContext,
  HostEventMap,
  SidebarEventMap,
} from "@/types/assistant-sidebar";
import { useActiveWorkspaceName } from "@/store/AppStore";
import { useToast } from "@/ui/use-toast";
import useWorkspace from "@/plugins/comet/useWorkspace";
import useProjectById from "@/api/projects/useProjectById";
import { BASE_API_URL } from "@/api/api";

const DEV_BASE_URL = import.meta.env.VITE_ASSISTANT_SIDEBAR_BASE_URL;

const ASSISTANT_BRIDGE_VERSION = 1;
const PROD_BASE = import.meta.env.VITE_ASSISTANT_SIDEBAR_CDN_URL;
const FAILURE_COOLDOWN_MS = 5 * 60 * 1000;
const FAILURE_KEY = "assistant_load_failure_ts";
const IS_DEV = import.meta.env.DEV;
const ASSISTANT_BACKEND_URL =
  import.meta.env.VITE_ASSISTANT_BACKEND_URL || "/assistant-api";

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
}

const isInCooldown = (): boolean => {
  const ts = sessionStorage.getItem(FAILURE_KEY);
  if (!ts) return false;
  return Date.now() - Number(ts) < FAILURE_COOLDOWN_MS;
};

const markFailure = (): void => {
  sessionStorage.setItem(FAILURE_KEY, String(Date.now()));
};

const clearFailure = (): void => {
  sessionStorage.removeItem(FAILURE_KEY);
};

async function fetchManifest(
  baseUrl: string,
  retry = true,
): Promise<AssistantManifest> {
  try {
    const res = await fetch(`${baseUrl}/manifest.json`);
    if (!res.ok) throw new Error(`manifest ${res.status}`);
    return (await res.json()) as AssistantManifest;
  } catch (err) {
    if (retry) return fetchManifest(baseUrl, false);
    throw err;
  }
}

type HostListeners = {
  [K in keyof HostEventMap]: Set<(data: HostEventMap[K]) => void>;
};

function createHostListeners(): HostListeners {
  return {
    "context:changed": new Set(),
    "visibility:changed": new Set(),
  };
}

interface BridgeRefs {
  navigate: React.MutableRefObject<(path: string) => void>;
  onWidthChange: React.MutableRefObject<(width: number) => void>;
  onNotification: React.MutableRefObject<
    (data: SidebarEventMap["notification"]) => void
  >;
  onRequestVisibility: React.MutableRefObject<(open: boolean) => void>;
  context: React.MutableRefObject<BridgeContext>;
  listeners: React.MutableRefObject<HostListeners>;
}

const createBridge = (refs: BridgeRefs): AssistantSidebarBridge => ({
  version: ASSISTANT_BRIDGE_VERSION,
  getContext: () => refs.context.current,
  subscribe: (event, callback) => {
    const set = refs.listeners.current[event as keyof HostEventMap] as
      | Set<typeof callback>
      | undefined;
    if (!set) return () => {};
    set.add(callback);
    return () => {
      set.delete(callback);
    };
  },
  emit: (event, data) => {
    if (event === "navigate") {
      refs.navigate.current((data as SidebarEventMap["navigate"]).path);
    } else if (event === "sidebar:resized") {
      refs.onWidthChange.current(
        (data as SidebarEventMap["sidebar:resized"]).width,
      );
    } else if (event === "notification") {
      refs.onNotification.current(data as SidebarEventMap["notification"]);
    } else if (event === "sidebar:request-open") {
      refs.onRequestVisibility.current(true);
    } else if (event === "sidebar:request-close") {
      refs.onRequestVisibility.current(false);
    } else if (IS_DEV) {
      console.warn(
        `[AssistantBridge] Unhandled sidebar event: "${event}"`,
        data,
      );
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

function useBridgeContext(): BridgeContext {
  const workspaceName = useActiveWorkspaceName();
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

  return useMemo<BridgeContext>(
    () => ({
      workspaceId,
      workspaceName,
      projectId: resolvedProjectId,
      projectName,
      baseApiUrl: BASE_API_URL,
      assistantBackendUrl: ASSISTANT_BACKEND_URL,
      theme: "light",
    }),
    [workspaceId, workspaceName, resolvedProjectId, projectName],
  );
}

interface AssistantMeta {
  scriptUrl: string;
  cssUrl?: string;
}

function useAssistantMeta(): AssistantMeta | null {
  const versionBase = `${PROD_BASE}/v${ASSISTANT_BRIDGE_VERSION}`;

  const { data } = useQuery<AssistantMeta>({
    queryKey: ["assistant-manifest", versionBase],
    queryFn: async () => {
      try {
        const manifest = await fetchManifest(versionBase);
        clearFailure();
        return {
          scriptUrl: `${versionBase}/${manifest.js}`,
          cssUrl: manifest.css ? `${versionBase}/${manifest.css}` : undefined,
        };
      } catch (err) {
        markFailure();
        throw err;
      }
    },
    enabled: !IS_DEV && !!PROD_BASE && !isInCooldown(),
    staleTime: Infinity,
    retry: false,
  });

  // In dev mode, return meta directly from env var — no fetch needed
  if (IS_DEV && DEV_BASE_URL) {
    return { scriptUrl: `${DEV_BASE_URL}/assistant.js` };
  }

  return data ?? null;
}

interface AssistantSidebarProps {
  onWidthChange: (width: number) => void;
}

const AssistantSidebar: React.FC<AssistantSidebarProps> = ({
  onWidthChange,
}) => {
  const meta = useAssistantMeta();
  const context = useBridgeContext();
  const router = useRouter();

  const { toast } = useToast();

  const contextRef = useLatestRef(context);
  const onWidthChangeRef = useLatestRef(onWidthChange);
  const listenersRef = useRef<HostListeners>(createHostListeners());

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

  const navigateRef = useLatestRef((path: string) => {
    const ws = contextRef.current.workspaceName;
    const fullPath = ws ? `/${ws}${path}` : path;
    router.navigate({ to: fullPath });
  });

  const bridgeRef = useRef(
    createBridge({
      navigate: navigateRef,
      onWidthChange: onWidthChangeRef,
      onNotification: onNotificationRef,
      onRequestVisibility: onRequestVisibilityRef,
      context: contextRef,
      listeners: listenersRef,
    }),
  );

  // Expose bridge and meta on window for iframe access
  useEffect(() => {
    window.opikBridge = bridgeRef.current;
    if (meta) {
      window.__opikAssistantMeta__ = meta;
    }
    return () => {
      delete window.opikBridge;
      delete window.__opikAssistantMeta__;
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

  if (!meta) return null;

  return (
    <iframe
      ref={setIframeRef}
      src="/assistant/shell"
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
