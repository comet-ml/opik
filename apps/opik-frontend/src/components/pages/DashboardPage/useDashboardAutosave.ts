import { useEffect, useRef, useState, useCallback, useMemo } from "react";
import debounce from "lodash/debounce";
import omit from "lodash/omit";
import isEqual from "fast-deep-equal";

import { useDashboardStore } from "@/store/DashboardStore";
import useDashboardUpdateMutation from "@/api/dashboards/useDashboardUpdateMutation";
import { DashboardState } from "@/types/dashboard";

const AUTOSAVE_DEBOUNCE_MS = 2000;

const OMIT_KEYS_ON_COMPARE = ["lastModified"];

const isConfigChanged = (
  current: DashboardState,
  previous: DashboardState | null,
): boolean => {
  if (!previous) return true;

  return !isEqual(
    omit(current, OMIT_KEYS_ON_COMPARE),
    omit(previous, OMIT_KEYS_ON_COMPARE),
  );
};

interface UseDashboardAutosaveOptions {
  dashboardId: string;
  enabled?: boolean;
}

interface UseDashboardAutosaveReturn {
  isSaving: boolean;
  hasUnsavedChanges: boolean;
}

export const useDashboardAutosave = ({
  dashboardId,
  enabled = true,
}: UseDashboardAutosaveOptions): UseDashboardAutosaveReturn => {
  const [hasUnsavedChanges, setHasUnsavedChanges] = useState(false);
  const lastSavedConfigRef = useRef<DashboardState | null>(null);
  const isInitializedRef = useRef(false);

  const { mutateAsync: updateDashboard, isPending } =
    useDashboardUpdateMutation();

  const performSave = useCallback(async () => {
    // Get the LATEST state from the store, not the debounced state
    const currentConfig = useDashboardStore.getState().getDashboardConfig();

    if (!isConfigChanged(currentConfig, lastSavedConfigRef.current)) return;

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
      console.error("Failed to autosave dashboard:", error);
    }
  }, [dashboardId, updateDashboard]);

  const debouncedSave = useMemo(
    () => debounce(performSave, AUTOSAVE_DEBOUNCE_MS),
    [performSave],
  );

  useEffect(() => {
    if (!enabled) return;

    const unsubscribe = useDashboardStore.subscribe((state) => {
      const config = state.getDashboardConfig();

      if (!isInitializedRef.current) {
        lastSavedConfigRef.current = config;
        isInitializedRef.current = true;
        return;
      }

      if (isConfigChanged(config, lastSavedConfigRef.current)) {
        setHasUnsavedChanges(true);
        debouncedSave();
      }
    });

    const handleBeforeUnload = () => debouncedSave.flush();
    window.addEventListener("beforeunload", handleBeforeUnload);

    return () => {
      unsubscribe();
      window.removeEventListener("beforeunload", handleBeforeUnload);
      debouncedSave.cancel();
    };
  }, [enabled, debouncedSave]);

  return {
    isSaving: isPending,
    hasUnsavedChanges,
  };
};
