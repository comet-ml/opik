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
import useAssistantBackend from "@/plugins/comet/useAssistantBackend";
import useProjectById from "@/api/projects/useProjectById";
import { BASE_API_URL } from "@/api/api";

const DEV_BASE_URL = import.meta.env.VITE_ASSISTANT_SIDEBAR_BASE_URL;
const IS_DEV = import.meta.env.DEV;
const BRIDGE_PROTOCOL_VERSION = 1;

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
  version: BRIDGE_PROTOCOL_VERSION,
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
    switch (event) {
      case "navigate":
        refs.navigate.current((data as SidebarEventMap["navigate"]).path);
        break;
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
      default:
        if (IS_DEV) {
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

function useBridgeContext(assistantBackendUrl: string): BridgeContext {
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
      assistantBackendUrl,
      theme: "light",
    }),
    [
      workspaceId,
      workspaceName,
      resolvedProjectId,
      projectName,
      assistantBackendUrl,
    ],
  );
}

interface AssistantMeta {
  scriptUrl: string;
  cssUrl?: string;
  shellUrl: string;
  version: string;
}

function useAssistantMeta(backendUrl: string | null): AssistantMeta | null {
  const manifestUrl = DEV_BASE_URL
    ? `${DEV_BASE_URL}/manifest.json`
    : backendUrl
      ? `${backendUrl}/console/manifest.json`
      : null;

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
        shellUrl: IS_DEV
          ? "/assistant/shell"
          : `${manifestBase}/${manifest.shell}`,
        version: manifest.ver,
      };
    },
    enabled: !!manifestUrl,
    staleTime: Infinity,
    retry: 1,
  });

  return data ?? null;
}

interface AssistantSidebarProps {
  onWidthChange: (width: number) => void;
}

const AssistantSidebar: React.FC<AssistantSidebarProps> = ({
  onWidthChange,
}) => {
  const { backendUrl, isReady: isBackendReady } = useAssistantBackend();
  const meta = useAssistantMeta(backendUrl);
  const context = useBridgeContext(backendUrl ?? "");
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

  if (!meta || !isBackendReady) return null;

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
