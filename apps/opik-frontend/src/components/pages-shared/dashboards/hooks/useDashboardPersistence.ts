import { useEffect, useRef, useState, useCallback } from "react";
import { debounce } from "lodash";

import { useDashboardStore } from "@/store/DashboardStore";
import useDashboardById from "@/api/dashboards/useDashboardById";
import useDashboardUpdateMutation from "@/api/dashboards/useDashboardUpdateMutation";
import { keepaliveSaveDashboard } from "@/api/dashboards/dashboardKeepaliveSave";
import { Dashboard, DashboardState } from "@/types/dashboard";
import { isDashboardChanged } from "@/lib/dashboard/utils";
import {
  loadLocal,
  saveLocal,
  clearLocal,
} from "@/lib/dashboard/dashboardLocalDb";

export type DashboardSaveStatus = "idle" | "saving" | "saved" | "error";

const AUTOSAVE_DEBOUNCE_MS = 2000;
const SAVED_STATUS_DISPLAY_MS = 3000;

interface UseDashboardPersistenceParams {
  dashboardId: string;
  enabled?: boolean;
}

interface UseDashboardPersistenceReturn {
  dashboard: Dashboard | undefined;
  isPending: boolean;
  resolvedConfig: DashboardState | null;
  saveStatus: DashboardSaveStatus;
}

export const useDashboardPersistence = ({
  dashboardId,
  enabled = true,
}: UseDashboardPersistenceParams): UseDashboardPersistenceReturn => {
  const { data: dashboard, isPending } = useDashboardById(
    { dashboardId },
    { enabled },
  );

  const { mutate: syncToServer } = useDashboardUpdateMutation({
    skipDefaultError: true,
  });

  const dashboardRef = useRef(dashboard);
  dashboardRef.current = dashboard;

  const [resolvedConfig, setResolvedConfig] = useState<DashboardState | null>(
    null,
  );

  useEffect(() => {
    const currentDashboard = dashboardRef.current;
    if (!currentDashboard?.config) return;

    let cancelled = false;

    loadLocal(currentDashboard.id).then((localEntry) => {
      if (cancelled) return;

      if (
        localEntry &&
        localEntry.savedAt >
          new Date(currentDashboard.last_updated_at).getTime() &&
        isDashboardChanged(localEntry.config, currentDashboard.config)
      ) {
        setResolvedConfig(localEntry.config);
        syncToServer(
          {
            dashboard: {
              id: currentDashboard.id,
              config: localEntry.config,
            },
          },
          { onSettled: () => clearLocal(currentDashboard.id) },
        );
      } else {
        setResolvedConfig(currentDashboard.config);
        if (localEntry) {
          clearLocal(currentDashboard.id);
        }
      }
    });

    return () => {
      cancelled = true;
      setResolvedConfig(null);
    };
  }, [dashboard?.id, syncToServer]);

  const lastSavedConfigRef = useRef<DashboardState | null>(null);
  const [saveStatus, setSaveStatus] = useState<DashboardSaveStatus>("idle");
  const savedTimerRef = useRef<ReturnType<typeof setTimeout>>();

  const { mutate: updateDashboard } = useDashboardUpdateMutation({
    skipDefaultError: true,
    retry: 1,
    retryDelay: AUTOSAVE_DEBOUNCE_MS * 2,
  });

  const performSave = useCallback(
    (targetDashboardId: string, config: DashboardState) => {
      if (!isDashboardChanged(config, lastSavedConfigRef.current)) return;

      setSaveStatus("saving");

      updateDashboard(
        {
          dashboard: {
            id: targetDashboardId,
            config,
          },
        },
        {
          onSuccess: () => {
            lastSavedConfigRef.current = config;
            clearLocal(targetDashboardId);
            setSaveStatus("saved");
            clearTimeout(savedTimerRef.current);
            savedTimerRef.current = setTimeout(() => {
              setSaveStatus("idle");
            }, SAVED_STATUS_DISPLAY_MS);
          },
          onError: () => {
            setSaveStatus("error");
          },
        },
      );
    },
    [updateDashboard],
  );

  useEffect(() => {
    if (!enabled || !dashboard?.id || !resolvedConfig) return;

    let prevLastModified = 0;

    const debouncedSave = debounce((config: DashboardState) => {
      performSave(dashboardId, config);
    }, AUTOSAVE_DEBOUNCE_MS);

    const unsubscribe = useDashboardStore.subscribe((state) => {
      if (state.lastModified === 0) return;
      if (state.lastModified === prevLastModified) return;
      prevLastModified = state.lastModified;

      const config = state.getDashboard();

      if (lastSavedConfigRef.current === null) {
        lastSavedConfigRef.current = config;
        return;
      }

      const hasChanges = isDashboardChanged(config, lastSavedConfigRef.current);

      if (hasChanges) {
        saveLocal(dashboardId, config);
        debouncedSave(config);
      }
    });

    const handleBeforeUnload = () => {
      debouncedSave.cancel();

      const config = useDashboardStore.getState().getDashboard();
      if (isDashboardChanged(config, lastSavedConfigRef.current)) {
        saveLocal(dashboardId, config);
        keepaliveSaveDashboard(dashboardId, config);
      }
    };

    window.addEventListener("beforeunload", handleBeforeUnload);

    return () => {
      window.removeEventListener("beforeunload", handleBeforeUnload);
      unsubscribe();
      debouncedSave.cancel();
      const state = useDashboardStore.getState();
      if (state.lastModified !== 0) {
        const config = state.getDashboard();
        if (isDashboardChanged(config, lastSavedConfigRef.current)) {
          performSave(dashboardId, config);
        }
      }
      lastSavedConfigRef.current = null;
      clearTimeout(savedTimerRef.current);
    };
  }, [enabled, dashboard?.id, resolvedConfig, dashboardId, performSave]);

  return {
    dashboard,
    isPending,
    resolvedConfig,
    saveStatus,
  };
};
