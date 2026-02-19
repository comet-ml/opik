import {
  createContext,
  Dispatch,
  SetStateAction,
  useContext,
  useEffect,
  useMemo,
  useState,
} from "react";
import noop from "lodash/noop";

import useWorkspaceConfig from "@/api/workspaces/useWorkspaceConfig";
import useAppStore from "@/store/AppStore";
import { WorkspaceConfig } from "@/types/workspaces";

type ServerSyncProviderProps = {
  children: React.ReactNode;
};

type ServerSyncState = {
  config: WorkspaceConfig | null;
  truncationEnabled: boolean;
  threadTimeout: string | null;
  colorMap: Record<string, string> | null;
  previewColor: Record<string, string>;
  setPreviewColor: Dispatch<SetStateAction<Record<string, string>>>;
};

const initialState: ServerSyncState = {
  config: null,
  truncationEnabled: true,
  threadTimeout: null,
  colorMap: null,
  previewColor: {},
  setPreviewColor: noop,
};

const ServerSyncProviderContext = createContext<ServerSyncState>(initialState);

/**
 * Provides workspace-scoped data synchronized from the backend.
 * Automatically refetches when workspace changes or data becomes stale.
 *
 * Synced data includes:
 * - Workspace configuration (truncation limits, timeouts)
 * - Future: Workspace limits, permissions, metadata
 *
 * Usage:
 * ```tsx
 * const { truncationLimit } = useServerSync();
 * // Or use convenience hook:
 * const limit = useTruncationLimit();
 * ```
 */
export function ServerSyncProvider({ children }: ServerSyncProviderProps) {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const { data: config } = useWorkspaceConfig({ workspaceName });
  const [previewColor, setPreviewColor] = useState<Record<string, string>>({});

  // Clear preview overrides once the server config refreshes,
  // so the merged colorMap switches from preview → actual server data without a blink.
  useEffect(() => setPreviewColor({}), [config]);

  const value = useMemo(() => {
    const truncationEnabled = config?.truncation_on_tables ?? true;
    const threadTimeout = config?.timeout_to_mark_thread_as_inactive ?? null;
    const serverMap = config?.color_map ?? null;
    const hasOverrides = Object.keys(previewColor).length > 0;
    const colorMap = hasOverrides
      ? { ...(serverMap ?? {}), ...previewColor }
      : serverMap;

    return {
      config: config ?? null,
      truncationEnabled,
      threadTimeout,
      colorMap,
      previewColor,
      setPreviewColor,
    };
  }, [config, previewColor]);

  return (
    <ServerSyncProviderContext.Provider value={value}>
      {children}
    </ServerSyncProviderContext.Provider>
  );
}

/**
 * Access all server-synced workspace data
 */
export const useServerSync = () => {
  const context = useContext(ServerSyncProviderContext);

  if (context === undefined) {
    throw new Error("useServerSync must be used within a ServerSyncProvider");
  }

  return context;
};

/**
 * Convenience hook to get truncation enabled state directly
 */
export const useTruncationEnabled = () => {
  const { truncationEnabled } = useServerSync();
  return truncationEnabled;
};

/**
 * Convenience hook to get workspace config directly
 */
export const useWorkspaceConfigSync = () => {
  const { config } = useServerSync();
  return config;
};
