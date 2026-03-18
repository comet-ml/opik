import { useEffect, useMemo } from "react";

import {
  useDashboardStore,
  selectSetWidgetResolver,
  selectClearDashboard,
  selectSetReadOnly,
} from "@/store/DashboardStore";
import { widgetResolver } from "@/components/pages-shared/dashboards/widgets/widgetRegistry";
import {
  useDashboardPersistence,
  DashboardSaveStatus,
} from "./useDashboardPersistence";
import {
  Dashboard,
  DASHBOARD_SCOPE,
  DASHBOARD_TYPE,
  TEMPLATE_SCOPE,
} from "@/types/dashboard";
import { isTemplateId } from "@/lib/dashboard/utils";
import { TEMPLATE_LIST } from "@/lib/dashboard/templates";

interface UseDashboardLifecycleParams {
  dashboardId: string | null;
  enabled?: boolean;
}

interface UseDashboardLifecycleReturn {
  dashboard: Dashboard | undefined;
  isPending: boolean;
  saveStatus: DashboardSaveStatus;
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
      type:
        template.scope === TEMPLATE_SCOPE.EXPERIMENTS
          ? DASHBOARD_TYPE.EXPERIMENTS
          : DASHBOARD_TYPE.MULTI_PROJECT,
      scope: DASHBOARD_SCOPE.INSIGHTS,
      created_at: "",
      last_updated_at: "",
    } as Dashboard;
  }, [isTemplate, dashboardId]);

  const loadDashboardFromBackend = useDashboardStore(
    (state) => state.loadDashboardFromBackend,
  );
  const clearDashboard = useDashboardStore(selectClearDashboard);
  const setWidgetResolver = useDashboardStore(selectSetWidgetResolver);
  const setReadOnly = useDashboardStore(selectSetReadOnly);

  const {
    dashboard: backendDashboard,
    isPending: isBackendPending,
    resolvedConfig,
    saveStatus,
  } = useDashboardPersistence({
    dashboardId: dashboardId || "",
    enabled: Boolean(dashboardId) && enabled && !isTemplate,
  });

  useEffect(() => {
    if (isTemplate && templateDashboard?.config) {
      loadDashboardFromBackend(templateDashboard.config);
      setReadOnly(true);
    } else if (resolvedConfig) {
      loadDashboardFromBackend(resolvedConfig);
      setReadOnly(false);
    } else {
      return;
    }

    return () => {
      clearDashboard();
    };
  }, [
    isTemplate,
    templateDashboard?.id,
    templateDashboard?.config,
    resolvedConfig,
    loadDashboardFromBackend,
    setReadOnly,
    clearDashboard,
  ]);

  useEffect(() => {
    setWidgetResolver(widgetResolver);
    return () => setWidgetResolver(null);
  }, [setWidgetResolver]);

  if (isTemplate) {
    return {
      dashboard: templateDashboard,
      isPending: false,
      saveStatus: "idle" as DashboardSaveStatus,
    };
  }

  return {
    dashboard: backendDashboard,
    isPending: isBackendPending,
    saveStatus,
  };
};
