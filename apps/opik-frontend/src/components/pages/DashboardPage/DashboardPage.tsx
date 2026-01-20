import React, { useEffect, useCallback } from "react";
import { useParams } from "@tanstack/react-router";

import {
  useDashboardStore,
  selectSetConfig,
  selectMixedConfig,
} from "@/store/DashboardStore";
import useBreadcrumbsStore from "@/store/BreadcrumbsStore";
import { useMetricDateRangeCore } from "@/components/pages-shared/traces/MetricDateRangeSelect/useMetricDateRangeCore";
import { DEFAULT_DATE_PRESET } from "@/components/pages-shared/traces/MetricDateRangeSelect/constants";
import MetricDateRangeSelect from "@/components/pages-shared/traces/MetricDateRangeSelect/MetricDateRangeSelect";
import { DateRangeSerializedValue } from "@/components/shared/DateRangeSelect";
import Loader from "@/components/shared/Loader/Loader";
import { Separator } from "@/components/ui/separator";
import { useDashboardLifecycle } from "@/components/pages-shared/dashboards/hooks/useDashboardLifecycle";
import DashboardSaveActions from "@/components/pages-shared/dashboards/DashboardSaveActions/DashboardSaveActions";
import DashboardConfigButton from "@/components/pages-shared/dashboards/DashboardConfigButton/DashboardConfigButton";
import ShareDashboardButton from "@/components/pages-shared/dashboards/ShareDashboardButton/ShareDashboardButton";
import DashboardContent from "@/components/pages-shared/dashboards/DashboardContent/DashboardContent";

const DashboardPage: React.FunctionComponent = () => {
  const { dashboardId } = useParams({ strict: false }) as {
    dashboardId: string;
  };
  const setBreadcrumbParam = useBreadcrumbsStore((state) => state.setParam);

  const { dashboard, isPending, save, discard } = useDashboardLifecycle({
    dashboardId,
    enabled: Boolean(dashboardId),
  });

  const config = useDashboardStore(selectMixedConfig);
  const setConfig = useDashboardStore(selectSetConfig);

  const dateRangeValue = config?.dateRange || DEFAULT_DATE_PRESET;

  useEffect(() => {
    if (dashboard?.name) {
      setBreadcrumbParam("dashboardId", dashboardId, dashboard.name);
    }
  }, [dashboardId, dashboard?.name, setBreadcrumbParam]);

  const handleDateRangeValueChange = useCallback(
    (value: DateRangeSerializedValue) => {
      if (!config) return;
      setConfig({ ...config, dateRange: value });
    },
    [config, setConfig],
  );

  const { dateRange, handleDateRangeChange, minDate, maxDate } =
    useMetricDateRangeCore({
      value: dateRangeValue,
      setValue: handleDateRangeValueChange,
    });

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
          <DashboardSaveActions
            onSave={save}
            onDiscard={discard}
            dashboard={dashboard}
          />
          <MetricDateRangeSelect
            value={dateRange}
            onChangeValue={handleDateRangeChange}
            minDate={minDate}
            maxDate={maxDate}
            hideAlltime
          />
          <Separator orientation="vertical" className="mx-2 h-4" />
          <ShareDashboardButton />
          <DashboardConfigButton />
        </div>
      </div>
      <div className="pb-4 pt-1">
        <DashboardContent />
      </div>
    </>
  );
};

export default DashboardPage;
