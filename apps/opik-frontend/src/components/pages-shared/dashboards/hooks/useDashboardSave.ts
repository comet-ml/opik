import { useEffect, useRef, useCallback } from "react";

import {
  useDashboardStore,
  selectSetHasUnsavedChanges,
} from "@/store/DashboardStore";
import useDashboardUpdateMutation from "@/api/dashboards/useDashboardUpdateMutation";
import { DashboardState } from "@/types/dashboard";
import { isDashboardChanged } from "@/lib/dashboard/utils";

interface UseDashboardSaveOptions {
  dashboardId: string;
  enabled?: boolean;
}

interface UseDashboardSaveReturn {
  save: () => Promise<void>;
  discard: () => void;
}

export const useDashboardSave = ({
  dashboardId,
  enabled = true,
}: UseDashboardSaveOptions): UseDashboardSaveReturn => {
  const setHasUnsavedChanges = useDashboardStore(selectSetHasUnsavedChanges);
  const lastSavedConfigRef = useRef<DashboardState | null>(null);

  const { mutateAsync: updateDashboard } = useDashboardUpdateMutation();

  const save = useCallback(async () => {
    const currentConfig = useDashboardStore.getState().getDashboard();

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
  }, [dashboardId, updateDashboard, setHasUnsavedChanges]);

  const discard = useCallback(() => {
    if (!lastSavedConfigRef.current) return;

    useDashboardStore
      .getState()
      .loadDashboardFromBackend(lastSavedConfigRef.current);
    setHasUnsavedChanges(false);
  }, [setHasUnsavedChanges]);

  useEffect(() => {
    if (!enabled) return;

    const unsubscribe = useDashboardStore.subscribe((state) => {
      const config = state.getDashboard();

      if (lastSavedConfigRef.current === null) {
        lastSavedConfigRef.current = config;
        return;
      }

      const hasChanges = isDashboardChanged(config, lastSavedConfigRef.current);

      if (hasChanges !== state.hasUnsavedChanges) {
        setHasUnsavedChanges(hasChanges);
      }
    });

    return () => {
      unsubscribe();
      lastSavedConfigRef.current = null;
      setHasUnsavedChanges(false);
    };
  }, [enabled, dashboardId, setHasUnsavedChanges]);

  return {
    save,
    discard,
  };
};
