import { useEffect } from "react";

import {
  useDashboardStore,
  selectSetWidgetResolver,
  selectClearDashboard,
} from "@/store/DashboardStore";
import useDashboardById from "@/api/dashboards/useDashboardById";
import { widgetResolver } from "@/components/shared/Dashboard/widgets/widgetRegistry";
import { useDashboardSave } from "./useDashboardSave";
import { Dashboard } from "@/types/dashboard";

interface UseDashboardLifecycleParams {
  dashboardId: string | null;
  enabled?: boolean;
}

interface UseDashboardLifecycleReturn {
  dashboard: Dashboard | undefined;
  isPending: boolean;
  hasUnsavedChanges: boolean;
  save: () => Promise<void>;
  discard: () => void;
}

export const useDashboardLifecycle = ({
  dashboardId,
  enabled = true,
}: UseDashboardLifecycleParams): UseDashboardLifecycleReturn => {
  const { data: dashboard, isPending } = useDashboardById(
    { dashboardId: dashboardId || "" },
    { enabled: Boolean(dashboardId) && enabled },
  );

  const loadDashboardFromBackend = useDashboardStore(
    (state) => state.loadDashboardFromBackend,
  );
  const clearDashboard = useDashboardStore(selectClearDashboard);
  const setWidgetResolver = useDashboardStore(selectSetWidgetResolver);

  useEffect(() => {
    if (dashboard?.config) {
      loadDashboardFromBackend(dashboard.config);
    }
    return () => clearDashboard();
  }, [clearDashboard, dashboard, loadDashboardFromBackend]);

  useEffect(() => {
    setWidgetResolver(widgetResolver);
    return () => setWidgetResolver(null);
  }, [setWidgetResolver]);

  const { hasUnsavedChanges, save, discard } = useDashboardSave({
    dashboardId: dashboardId || "",
    enabled: Boolean(dashboardId && dashboard) && enabled,
  });

  return {
    dashboard,
    isPending,
    hasUnsavedChanges,
    save,
    discard,
  };
};
