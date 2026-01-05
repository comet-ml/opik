import { useEffect, useMemo } from "react";

import {
  useDashboardStore,
  selectSetWidgetResolver,
  selectClearDashboard,
} from "@/store/DashboardStore";
import useDashboardById from "@/api/dashboards/useDashboardById";
import { widgetResolver } from "@/components/shared/Dashboard/widgets/widgetRegistry";
import { useDashboardSave } from "./useDashboardSave";
import { Dashboard } from "@/types/dashboard";
import { isTemplateId } from "@/lib/dashboard/utils";
import { TEMPLATE_LIST } from "@/lib/dashboard/templates";

interface UseDashboardLifecycleParams {
  dashboardId: string | null;
  enabled?: boolean;
}

interface UseDashboardLifecycleReturn {
  dashboard: Dashboard | undefined;
  isPending: boolean;
  save: () => Promise<void>;
  discard: () => void;
  isTemplate: boolean;
}

export const useDashboardLifecycle = ({
  dashboardId,
  enabled = true,
}: UseDashboardLifecycleParams): UseDashboardLifecycleReturn => {
  const isTemplate = isTemplateId(dashboardId);

  const templateDashboard = useMemo(() => {
    if (!isTemplate || !dashboardId) return undefined;

    const template = TEMPLATE_LIST.find((t) => t.id === dashboardId);
    if (!template) return undefined;

    return {
      id: template.id,
      name: template.name,
      description: template.description,
      workspace_id: "",
      config: template.config,
      created_at: "",
      last_updated_at: "",
    } as Dashboard;
  }, [isTemplate, dashboardId]);

  const { data: backendDashboard, isPending: isBackendPending } =
    useDashboardById(
      { dashboardId: dashboardId || "" },
      { enabled: Boolean(dashboardId) && enabled && !isTemplate },
    );

  const dashboard = isTemplate ? templateDashboard : backendDashboard;
  const isPending = isTemplate ? false : isBackendPending;

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

  const { save, discard } = useDashboardSave({
    dashboardId: dashboardId || "",
    enabled: Boolean(dashboardId && dashboard) && enabled,
  });

  return {
    dashboard,
    isPending,
    save,
    discard,
    isTemplate,
  };
};
