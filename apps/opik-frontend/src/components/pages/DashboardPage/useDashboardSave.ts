import { useEffect, useRef, useState, useCallback } from "react";

import { useDashboardStore } from "@/store/DashboardStore";
import useDashboardUpdateMutation from "@/api/dashboards/useDashboardUpdateMutation";
import { DashboardState } from "@/types/dashboard";
import { isDashboardChanged } from "@/lib/dashboard/utils";

interface UseDashboardSaveOptions {
  dashboardId: string;
  enabled?: boolean;
}

interface UseDashboardSaveReturn {
  hasUnsavedChanges: boolean;
  save: () => Promise<void>;
  discard: () => void;
}

export const useDashboardSave = ({
  dashboardId,
  enabled = true,
}: UseDashboardSaveOptions): UseDashboardSaveReturn => {
  const [hasUnsavedChanges, setHasUnsavedChanges] = useState(false);
  const lastSavedConfigRef = useRef<DashboardState | null>(null);
  const isInitializedRef = useRef(false);

  const { mutateAsync: updateDashboard } = useDashboardUpdateMutation();

  const save = useCallback(async () => {
    const currentConfig = useDashboardStore.getState().getDashboardConfig();

    if (!isDashboardChanged(currentConfig, lastSavedConfigRef.current)) return;

    try {
      await updateDashboard({
        dashboard: {
          id: dashboardId,
          config: currentConfig,
        },
      });

      lastSavedConfigRef.current = currentConfig;
      setHasUnsavedChanges(false);
    } catch (error) {
      console.error("Failed to save dashboard:", error);
    }
  }, [dashboardId, updateDashboard]);

  const discard = useCallback(() => {
    if (!lastSavedConfigRef.current) return;

    useDashboardStore
      .getState()
      .loadDashboardFromBackend(lastSavedConfigRef.current);
    setHasUnsavedChanges(false);
  }, []);

  useEffect(() => {
    if (!enabled) return;

    const unsubscribe = useDashboardStore.subscribe((state) => {
      const config = state.getDashboardConfig();

      if (!isInitializedRef.current) {
        lastSavedConfigRef.current = config;
        isInitializedRef.current = true;
        return;
      }

      setHasUnsavedChanges(
        isDashboardChanged(config, lastSavedConfigRef.current),
      );
    });

    return () => {
      unsubscribe();
      isInitializedRef.current = false;
      lastSavedConfigRef.current = null;
      setHasUnsavedChanges(false);
    };
  }, [enabled, dashboardId]);

  return {
    hasUnsavedChanges,
    save,
    discard,
  };
};
