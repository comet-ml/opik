import React, { useEffect, useState, useCallback } from "react";
import { useParams } from "@tanstack/react-router";

import {
  useDashboardStore,
  selectAddSection,
  selectAddWidget,
  selectSetConfig,
  selectSetSearch,
  selectClearConfig,
  selectClearDashboard,
  selectSetOnAddWidgetCallback,
  selectSetWidgetResolver,
} from "@/store/DashboardStore";
import useDashboardById from "@/api/dashboards/useDashboardById";
import useBreadcrumbsStore from "@/store/BreadcrumbsStore";
import { useMetricDateRangeWithQuery } from "@/components/pages-shared/traces/MetricDateRangeSelect/useMetricDateRangeWithQuery";
import MetricDateRangeSelect from "@/components/pages-shared/traces/MetricDateRangeSelect/MetricDateRangeSelect";
import DashboardSectionsContainer from "@/components/shared/Dashboard/Dashboard";
import AddSectionButton from "@/components/shared/Dashboard/DashboardSection/AddSectionButton";
import { WidgetConfigDialog } from "@/components/shared/Dashboard/WidgetConfigDialog";
import Loader from "@/components/shared/Loader/Loader";
import useNavigationBlocker from "@/hooks/useNavigationBlocker";
import { useDashboardSave } from "./useDashboardSave";
import DashboardSaveActions from "./DashboardSaveActions";
import {
  ProjectDashboardConfig,
  DashboardWidget,
  WIDGET_TYPE,
  AddWidgetConfig,
} from "@/types/dashboard";
import { createWidgetResolver } from "@/components/shared/Dashboard/widgets/widgetRegistry";

const DashboardPage: React.FunctionComponent = () => {
  const { dashboardId } = useParams({ strict: false }) as {
    dashboardId: string;
  };
  const setBreadcrumbParam = useBreadcrumbsStore((state) => state.setParam);
  const [search] = useState("");
  const [addWidgetDialogOpen, setAddWidgetDialogOpen] = useState(false);
  const [targetSectionId, setTargetSectionId] = useState<string | null>(null);

  const { data: dashboard, isPending } = useDashboardById(
    { dashboardId },
    { enabled: Boolean(dashboardId) },
  );

  const addSection = useDashboardStore(selectAddSection);
  const addWidget = useDashboardStore(selectAddWidget);
  const loadDashboardFromBackend = useDashboardStore(
    (state) => state.loadDashboardFromBackend,
  );
  const setConfig = useDashboardStore(selectSetConfig);
  const setSearch = useDashboardStore(selectSetSearch);
  const clearConfig = useDashboardStore(selectClearConfig);
  const clearDashboard = useDashboardStore(selectClearDashboard);
  const setOnAddWidgetCallback = useDashboardStore(
    selectSetOnAddWidgetCallback,
  );
  const setWidgetResolver = useDashboardStore(selectSetWidgetResolver);

  useEffect(() => {
    if (dashboard?.config) {
      loadDashboardFromBackend(dashboard.config);
    }
  }, [dashboard, loadDashboardFromBackend]);

  const { hasUnsavedChanges, save, discard } = useDashboardSave({
    dashboardId,
    enabled: Boolean(dashboardId && dashboard),
  });

  const { DialogComponent } = useNavigationBlocker({
    condition: hasUnsavedChanges,
    title: "You have unsaved changes",
    description:
      "If you leave now, your changes will be lost. Are you sure you want to continue?",
    confirmText: "Leave without saving",
    cancelText: "Stay on page",
  });

  useEffect(() => {
    if (dashboard?.name) {
      setBreadcrumbParam("dashboardId", dashboardId, dashboard.name);
    }
  }, [dashboardId, dashboard?.name, setBreadcrumbParam]);

  const {
    dateRange,
    handleDateRangeChange,
    interval,
    intervalStart,
    intervalEnd,
    minDate,
    maxDate,
  } = useMetricDateRangeWithQuery({
    key: "dashboard_date_range",
  });

  const handleOpenAddWidgetDialog = useCallback((sectionId: string) => {
    setTargetSectionId(sectionId);
    setAddWidgetDialogOpen(true);
  }, []);

  const handleSaveWidget = (widgetData: Partial<DashboardWidget>) => {
    if (targetSectionId && widgetData.type && widgetData.title) {
      addWidget(targetSectionId, {
        type: widgetData.type as WIDGET_TYPE,
        title: widgetData.title,
        subtitle: widgetData.subtitle,
        config: widgetData.config || {},
      } as AddWidgetConfig);
    }
  };

  useEffect(() => {
    const config: ProjectDashboardConfig = {
      projectId: "",
      interval,
      intervalStart,
      intervalEnd,
    };
    setConfig(config);
  }, [interval, intervalStart, intervalEnd, setConfig]);

  useEffect(() => {
    setSearch(search);
  }, [search, setSearch]);

  useEffect(() => {
    setOnAddWidgetCallback(handleOpenAddWidgetDialog);
  }, [handleOpenAddWidgetDialog, setOnAddWidgetCallback]);

  useEffect(() => {
    const resolver = createWidgetResolver();
    setWidgetResolver(resolver);
  }, [setWidgetResolver]);

  useEffect(() => {
    return () => {
      clearDashboard();
      clearConfig();
      setOnAddWidgetCallback(null);
      setWidgetResolver(null);
    };
  }, [clearDashboard, clearConfig, setOnAddWidgetCallback, setWidgetResolver]);

  if (isPending) {
    return <Loader />;
  }

  if (!dashboard) {
    return (
      <div className="flex h-full items-center justify-center">
        <p className="text-muted-foreground">Dashboard not found</p>
      </div>
    );
  }

  return (
    <div className="flex h-full flex-col">
      <div className="flex items-center justify-between pb-4 pt-6">
        <div className="flex flex-col gap-1">
          <h1 className="text-2xl font-medium text-foreground">
            {dashboard.name}
          </h1>
          {dashboard.description && (
            <p className="text-base text-muted-slate">
              {dashboard.description}
            </p>
          )}
        </div>
        <div className="flex items-center gap-2">
          <DashboardSaveActions
            hasUnsavedChanges={hasUnsavedChanges}
            onSave={save}
            onDiscard={discard}
          />
          <MetricDateRangeSelect
            value={dateRange}
            onChangeValue={handleDateRangeChange}
            minDate={minDate}
            maxDate={maxDate}
          />
        </div>
      </div>

      <div className="flex-1 overflow-auto">
        <div className="text-clip rounded-md">
          <DashboardSectionsContainer />

          <AddSectionButton onAddSection={addSection} />
        </div>

        <WidgetConfigDialog
          open={addWidgetDialogOpen}
          onOpenChange={setAddWidgetDialogOpen}
          sectionId={targetSectionId || ""}
          onSave={handleSaveWidget}
        />
      </div>

      {DialogComponent}
    </div>
  );
};

export default DashboardPage;
