import React, { useEffect } from "react";
import { useParams } from "@tanstack/react-router";

import {
  useDashboardStore,
  selectSetRuntimeConfig,
} from "@/store/DashboardStore";
import useBreadcrumbsStore from "@/store/BreadcrumbsStore";
import {
  useMetricDateRangeWithQueryAndStorage,
  MetricDateRangeSelect,
} from "@/components/pages-shared/traces/MetricDateRangeSelect";
import { DASHBOARD_TYPE } from "@/types/dashboard";
import Loader from "@/components/shared/Loader/Loader";
import { useDashboardLifecycle } from "@/components/pages-shared/dashboards/hooks/useDashboardLifecycle";
import DashboardAutoSaveIndicator from "@/components/pages-shared/dashboards/DashboardAutoSaveIndicator/DashboardAutoSaveIndicator";
import ShareDashboardButton from "@/components/pages-shared/dashboards/ShareDashboardButton/ShareDashboardButton";
import DashboardContent from "@/components/pages-shared/dashboards/DashboardContent/DashboardContent";

const DashboardPage: React.FunctionComponent = () => {
  const { dashboardId } = useParams({ strict: false }) as {
    dashboardId: string;
  };
  const setBreadcrumbParam = useBreadcrumbsStore((state) => state.setParam);

  const { dashboard, isPending, saveStatus } = useDashboardLifecycle({
    dashboardId,
    enabled: Boolean(dashboardId),
  });

  const setRuntimeConfig = useDashboardStore(selectSetRuntimeConfig);

  const showDateRange = dashboard?.type !== DASHBOARD_TYPE.EXPERIMENTS;

  const { dateRange, handleDateRangeChange, minDate, maxDate, dateRangeValue } =
    useMetricDateRangeWithQueryAndStorage({
      key: "dashboard_time_range",
      localStorageKey: "opik-workspace-dashboard-daterange",
    });

  useEffect(() => {
    setRuntimeConfig({
      dateRange: dateRangeValue,
      dashboardType: dashboard?.type,
    });
  }, [dateRangeValue, dashboard?.type, setRuntimeConfig]);

  useEffect(() => {
    if (dashboard?.name) {
      setBreadcrumbParam("dashboardId", dashboardId, dashboard.name);
    }
  }, [dashboardId, dashboard?.name, setBreadcrumbParam]);

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
    <>
      <div className="sticky top-0 z-10 -mx-6 flex items-center justify-between gap-4 bg-soft-background px-6 pb-3 pt-6">
        <div className="flex min-w-0 flex-1 flex-col gap-1">
          <h1 className="comet-title-l truncate break-words">
            {dashboard.name}
          </h1>
          {dashboard.description && (
            <p className="line-clamp-3 break-words text-base text-muted-slate">
              {dashboard.description}
            </p>
          )}
        </div>
        <div className="flex shrink-0 items-center gap-2">
          <DashboardAutoSaveIndicator saveStatus={saveStatus} />
          {showDateRange && (
            <MetricDateRangeSelect
              value={dateRange}
              onChangeValue={handleDateRangeChange}
              minDate={minDate}
              maxDate={maxDate}
              hideAlltime
            />
          )}
          <ShareDashboardButton />
        </div>
      </div>
      <div className="pb-4 pt-1">
        <DashboardContent />
      </div>
    </>
  );
};

export default DashboardPage;
