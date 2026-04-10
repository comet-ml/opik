import { RunnerConnectionStatus } from "@/types/agent-sandbox";

export type BridgeTheme = "light" | "dark";
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
  projectStats?: ProjectStats;
}

export interface RunnerBridgeState {
  projectId: string;
  status: RunnerConnectionStatus;
  pairCode: string | null;
  expiresAt: number | null;
  runnerId: string | null;
}

/** Host → Sidebar events */
export interface HostEventMap {
  "context:changed": BridgeContext;
  "visibility:changed": { isOpen: boolean };
  "runner:state-changed": RunnerBridgeState;
}

/** Sidebar → Host events */
export interface SidebarEventMap {
  navigate: { path: string; search?: Record<string, string> };
  notification: { message: string; type: NotificationType };
  "sidebar:resized": { width: number };
  "sidebar:request-close": Record<string, never>;
  "sidebar:request-open": Record<string, never>;
  "runner:request-pair": { projectId: string };
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
