import React, { useCallback, useEffect, useMemo, useRef } from "react";
import { useParams } from "@tanstack/react-router";
import { useIsFeatureEnabled } from "@/components/feature-toggles-provider";
import { FeatureToggleKeys } from "@/types/feature-toggles";
import {
  AssistantSidebarBridge,
  BridgeContext,
  HostEventMap,
} from "@/types/assistant-sidebar";
import { useUserApiKey, useActiveWorkspaceName } from "@/store/AppStore";
import useWorkspace from "@/plugins/comet/useWorkspace";
import useProjectById from "@/api/projects/useProjectById";

const BASE_URL = import.meta.env.VITE_ASSISTANT_SIDEBAR_URL || "/ollie";

interface AssistantSidebarProps {
  onWidthChange: (width: number) => void;
}

const loadScript = (src: string): Promise<void> =>
  new Promise((resolve, reject) => {
    if (document.querySelector(`script[src="${src}"]`)) {
      resolve();
      return;
    }
    const script = document.createElement("script");
    script.src = src;
    script.onload = () => resolve();
    script.onerror = () => reject(new Error(`Failed to load script: ${src}`));
    document.head.appendChild(script);
  });

const loadStylesheet = (href: string): void => {
  if (document.querySelector(`link[href="${href}"]`)) return;
  const link = document.createElement("link");
  link.rel = "stylesheet";
  link.href = href;
  document.head.appendChild(link);
};

type ContextChangedListener = (data: HostEventMap["context:changed"]) => void;

const createBridge = (
  onWidthChangeRef: React.MutableRefObject<(w: number) => void>,
  contextRef: React.MutableRefObject<BridgeContext>,
  listenersRef: React.MutableRefObject<Set<ContextChangedListener>>,
): AssistantSidebarBridge => ({
  version: 1,
  getContext: () => contextRef.current,
  subscribe: (event, callback) => {
    if (event === "context:changed") {
      const listener = callback as ContextChangedListener;
      listenersRef.current.add(listener);
      return () => {
        listenersRef.current.delete(listener);
      };
    }
    return () => {};
  },
  emit: (event, data) => {
    if (event === "sidebar:resized") {
      onWidthChangeRef.current((data as { width: number }).width);
    }
  },
});

// --- Suspense resource (module singleton) ---
let status: "idle" | "pending" | "resolved" | "rejected" = "idle";
let promise: Promise<void>;

function suspendUntilScript(): boolean {
  switch (status) {
    case "resolved":
      return true;
    case "rejected":
      return false;
    case "pending":
      throw promise;
    case "idle": {
      status = "pending";
      promise = (async () => {
        loadStylesheet(`${BASE_URL}/ollie.css`);
        await loadScript(`${BASE_URL}/ollie.js`);
      })().then(
        () => {
          status = "resolved";
        },
        () => {
          status = "rejected";
        },
      );
      throw promise;
    }
  }
}

function useBridgeContext(): BridgeContext {
  const apiKey = useUserApiKey();
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
      authToken: apiKey,
      baseApiUrl: "/api",
      assistantBackendUrl: "/ollie-assist",
      theme: "light",
    }),
    [workspaceId, workspaceName, resolvedProjectId, projectName, apiKey],
  );
}

const AssistantSidebarContent: React.FC<AssistantSidebarProps> = ({
  onWidthChange,
}) => {
  const scriptReady = suspendUntilScript();
  const context = useBridgeContext();

  const onWidthChangeRef = useRef(onWidthChange);
  onWidthChangeRef.current = onWidthChange;

  const contextRef = useRef(context);
  contextRef.current = context;

  const listenersRef = useRef<Set<ContextChangedListener>>(new Set());
  const bridgeRef = useRef(
    createBridge(onWidthChangeRef, contextRef, listenersRef),
  );
  const mountedElRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    for (const listener of listenersRef.current) {
      listener(context);
    }
  }, [context]);

  const containerRef = useCallback((el: HTMLDivElement | null) => {
    if (mountedElRef.current && window.OllieConsole) {
      window.OllieConsole.unmount(mountedElRef.current);
      mountedElRef.current = null;
      onWidthChangeRef.current(0);
    }
    if (el && window.OllieConsole) {
      window.OllieConsole.mount(el, bridgeRef.current);
      mountedElRef.current = el;
    }
  }, []);

  if (!scriptReady) return null;

  return (
    <div className="comet-assistant-sidebar-root absolute bottom-0 right-0 top-[var(--banner-height)] z-10">
      <div ref={containerRef} className="h-full" />
    </div>
  );
};

const AssistantSidebar: React.FC<AssistantSidebarProps> = ({
  onWidthChange,
}) => {
  const isEnabled = useIsFeatureEnabled(
    FeatureToggleKeys.ASSISTANT_SIDEBAR_ENABLED,
  );
  if (!isEnabled) return null;
  return <AssistantSidebarContent onWidthChange={onWidthChange} />;
};

export default AssistantSidebar;
