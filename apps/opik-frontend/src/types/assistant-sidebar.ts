export type BridgeTheme = "light" | "dark";
export type NotificationType = "success" | "error" | "info";

export interface BridgeContext {
  workspaceId: string;
  workspaceName: string;
  projectId: string | null;
  projectName: string | null;
  authToken: string;
  baseApiUrl: string;
  assistantBackendUrl: string;
  theme: BridgeTheme;
}

/** Host → Sidebar events */
export interface HostEventMap {
  "context:changed": BridgeContext;
}

/** Sidebar → Host events */
export interface SidebarEventMap {
  navigate: { path: string };
  notification: { message: string; type: NotificationType };
  "sidebar:resized": { width: number };
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
    OllieConsole?: {
      mount: (element: HTMLElement, bridge: AssistantSidebarBridge) => void;
      unmount: (element: HTMLElement) => void;
    };
  }
}
