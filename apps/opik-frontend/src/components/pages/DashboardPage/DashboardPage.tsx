import React, { useEffect, useState, useCallback } from "react";
import { useParams } from "@tanstack/react-router";

import {
  useDashboardStore,
  selectAddSection,
  selectAddWidget,
  selectUpdateWidget,
  selectSetConfig,
  selectClearConfig,
  selectClearDashboard,
  selectSetOnAddEditWidgetCallback,
  selectSetWidgetResolver,
  selectConfig,
} from "@/store/DashboardStore";
import useDashboardById from "@/api/dashboards/useDashboardById";
import useBreadcrumbsStore from "@/store/BreadcrumbsStore";
import { useMetricDateRangeCore } from "@/components/pages-shared/traces/MetricDateRangeSelect/useMetricDateRangeCore";
import { DEFAULT_DATE_PRESET } from "@/components/pages-shared/traces/MetricDateRangeSelect/constants";
import MetricDateRangeSelect from "@/components/pages-shared/traces/MetricDateRangeSelect/MetricDateRangeSelect";
import { DateRangeSerializedValue } from "@/components/shared/DateRangeSelect";
import DashboardSectionsContainer from "@/components/shared/Dashboard/Dashboard";
import AddSectionButton from "@/components/shared/Dashboard/DashboardSection/AddSectionButton";
import { WidgetConfigDialog } from "@/components/shared/Dashboard/WidgetConfigDialog";
import Loader from "@/components/shared/Loader/Loader";
import useNavigationBlocker from "@/hooks/useNavigationBlocker";
import { Separator } from "@/components/ui/separator";
import { useDashboardSave } from "./useDashboardSave";
import DashboardSaveActions from "./DashboardSaveActions";
import ShareDashboardButton from "./ShareDashboardButton";
import {
  ProjectDashboardConfig,
  DashboardWidget,
  WIDGET_TYPE,
  AddWidgetConfig,
  UpdateWidgetConfig,
  AddEditWidgetCallbackParams,
} from "@/types/dashboard";
import { createWidgetResolver } from "@/components/shared/Dashboard/widgets/widgetRegistry";

const DashboardPage: React.FunctionComponent = () => {
  const { dashboardId } = useParams({ strict: false }) as {
    dashboardId: string;
  };
  const setBreadcrumbParam = useBreadcrumbsStore((state) => state.setParam);
  const [widgetDialogOpen, setWidgetDialogOpen] = useState(false);
  const [targetSectionId, setTargetSectionId] = useState<string | null>(null);
  const [targetWidgetId, setTargetWidgetId] = useState<string | null>(null);

  const { data: dashboard, isPending } = useDashboardById(
    { dashboardId },
    { enabled: Boolean(dashboardId) },
  );

  const addSection = useDashboardStore(selectAddSection);
  const addWidget = useDashboardStore(selectAddWidget);
  const updateWidget = useDashboardStore(selectUpdateWidget);
  const loadDashboardFromBackend = useDashboardStore(
    (state) => state.loadDashboardFromBackend,
  );
  const config = useDashboardStore(selectConfig);
  const setConfig = useDashboardStore(selectSetConfig);
  const clearConfig = useDashboardStore(selectClearConfig);
  const clearDashboard = useDashboardStore(selectClearDashboard);
  const setOnAddEditWidgetCallback = useDashboardStore(
    selectSetOnAddEditWidgetCallback,
  );
  const setWidgetResolver = useDashboardStore(selectSetWidgetResolver);

  const dateRangeValue =
    (config as ProjectDashboardConfig)?.dateRange || DEFAULT_DATE_PRESET;

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

  const handleDateRangeValueChange = useCallback(
    (value: DateRangeSerializedValue) => {
      setConfig({
        ...(config as ProjectDashboardConfig),
        dateRange: value,
      });
    },
    [config, setConfig],
  );

  const { dateRange, handleDateRangeChange, minDate, maxDate } =
    useMetricDateRangeCore({
      value: dateRangeValue,
      setValue: handleDateRangeValueChange,
    });

  const handleOpenWidgetDialog = useCallback(
    ({ sectionId, widgetId }: AddEditWidgetCallbackParams) => {
      setTargetSectionId(sectionId);
      setTargetWidgetId(widgetId || null);
      setWidgetDialogOpen(true);
    },
    [],
  );

  const handleSaveWidget = (widgetData: Partial<DashboardWidget>) => {
    if (!targetSectionId) return;

    if (targetWidgetId) {
      updateWidget(targetSectionId, targetWidgetId, {
        title: widgetData.title,
        subtitle: widgetData.subtitle,
        config: widgetData.config,
      } as UpdateWidgetConfig);
    } else if (widgetData.type && widgetData.title) {
      addWidget(targetSectionId, {
        type: widgetData.type as WIDGET_TYPE,
        title: widgetData.title,
        subtitle: widgetData.subtitle,
        config: widgetData.config || {},
      } as AddWidgetConfig);
    }
  };

  useEffect(() => {
    setOnAddEditWidgetCallback(handleOpenWidgetDialog);
  }, [handleOpenWidgetDialog, setOnAddEditWidgetCallback]);

  useEffect(() => {
    const resolver = createWidgetResolver();
    setWidgetResolver(resolver);
  }, [setWidgetResolver]);

  useEffect(() => {
    return () => {
      clearDashboard();
      clearConfig();
      setOnAddEditWidgetCallback(null);
      setWidgetResolver(null);
    };
  }, [
    clearDashboard,
    clearConfig,
    setOnAddEditWidgetCallback,
    setWidgetResolver,
  ]);

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
          {hasUnsavedChanges && (
            <Separator orientation="vertical" className="mx-2 h-4" />
          )}
          <MetricDateRangeSelect
            value={dateRange}
            onChangeValue={handleDateRangeChange}
            minDate={minDate}
            maxDate={maxDate}
            hideAlltime
          />
          <Separator orientation="vertical" className="mx-2 h-4" />
          <ShareDashboardButton />
        </div>
      </div>

      <div className="flex-1 overflow-auto">
        <DashboardSectionsContainer />

        <div className="text-clip rounded-md">
          <AddSectionButton onAddSection={addSection} />
        </div>

        <WidgetConfigDialog
          open={widgetDialogOpen}
          onOpenChange={setWidgetDialogOpen}
          sectionId={targetSectionId || ""}
          widgetId={targetWidgetId || undefined}
          onSave={handleSaveWidget}
        />
      </div>

      {DialogComponent}
    </div>
  );
};

export default DashboardPage;
