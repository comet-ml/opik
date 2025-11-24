import React, { useEffect, useMemo, useState, useCallback } from "react";
import { keepPreviousData } from "@tanstack/react-query";
import { useParams } from "@tanstack/react-router";

import {
  useDashboardStore,
  selectAddSection,
  selectAddWidget,
  selectSetConfig,
  selectSetSearch,
  selectClearConfig,
  selectSetOnAddWidgetCallback,
} from "@/store/DashboardStore";
import { METRIC_NAME_TYPE } from "@/api/projects/useProjectMetric";
import useProjectsList from "@/api/projects/useProjectsList";
import useDashboardById from "@/api/dashboards/useDashboardById";
import useAppStore from "@/store/AppStore";
import useBreadcrumbsStore from "@/store/BreadcrumbsStore";
import { useMetricDateRangeWithQuery } from "@/components/pages-shared/traces/MetricDateRangeSelect/useMetricDateRangeWithQuery";
import MetricDateRangeSelect from "@/components/pages-shared/traces/MetricDateRangeSelect/MetricDateRangeSelect";
import DashboardSectionsContainer from "@/components/shared/Dashboard/DashboardSectionsContainer";
import AddSectionButton from "@/components/shared/Dashboard/AddSectionButton";
import AddWidgetDialog from "@/components/shared/Dashboard/AddWidgetDialog";
import Loader from "@/components/shared/Loader/Loader";
import { useDashboardAutosave } from "./useDashboardAutosave";
import { CheckCircle2, Loader2 } from "lucide-react";
import { cn } from "@/lib/utils";
import { ProjectDashboardConfig } from "@/types/dashboard";

const DashboardPage: React.FunctionComponent = () => {
  const { dashboardId } = useParams({ strict: false }) as {
    dashboardId: string;
  };
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
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
  const setOnAddWidgetCallback = useDashboardStore(
    selectSetOnAddWidgetCallback,
  );

  useEffect(() => {
    if (dashboard?.config) {
      loadDashboardFromBackend(dashboard.config);
    }
  }, [dashboard, loadDashboardFromBackend]);

  const { isSaving, hasUnsavedChanges } = useDashboardAutosave({
    dashboardId,
    enabled: Boolean(dashboardId && dashboard),
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

  const { data: projectsData } = useProjectsList(
    {
      workspaceName,
      sorting: [
        {
          desc: true,
          id: "last_updated_trace_at",
        },
      ],
      page: 1,
      size: 1,
    },
    {
      placeholderData: keepPreviousData,
    },
  );

  const defaultProject = useMemo(
    () => projectsData?.content?.[0],
    [projectsData],
  );

  const handleOpenAddWidgetDialog = useCallback((sectionId: string) => {
    setTargetSectionId(sectionId);
    setAddWidgetDialogOpen(true);
  }, []);

  const handleAddWidget = (metricType: METRIC_NAME_TYPE, title: string) => {
    if (targetSectionId) {
      addWidget(targetSectionId, "chart", title, metricType);
    }
  };

  useEffect(() => {
    const config: ProjectDashboardConfig = {
      projectId: defaultProject?.id || "",
      interval,
      intervalStart,
      intervalEnd,
    };
    setConfig(config);
  }, [defaultProject?.id, interval, intervalStart, intervalEnd, setConfig]);

  useEffect(() => {
    setSearch(search);
  }, [search, setSearch]);

  useEffect(() => {
    setOnAddWidgetCallback(handleOpenAddWidgetDialog);
  }, [handleOpenAddWidgetDialog, setOnAddWidgetCallback]);

  useEffect(() => {
    return () => {
      clearConfig();
      setOnAddWidgetCallback(null);
    };
  }, [clearConfig, setOnAddWidgetCallback]);

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
    <div className="flex h-full flex-col px-6">
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
          <div
            className={cn(
              "flex items-center gap-1.5 text-sm transition-opacity",
              !isSaving && !hasUnsavedChanges && "opacity-0",
            )}
          >
            {isSaving ? (
              <>
                <Loader2 className="size-4 animate-spin text-muted-foreground" />
                <span className="text-muted-foreground">Saving...</span>
              </>
            ) : (
              <>
                <CheckCircle2 className="size-4 text-green-600" />
                <span className="text-muted-foreground">Saved</span>
              </>
            )}
          </div>
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

        <AddWidgetDialog
          open={addWidgetDialogOpen}
          onOpenChange={setAddWidgetDialogOpen}
          onAddWidget={handleAddWidget}
        />
      </div>
    </div>
  );
};

export default DashboardPage;
