import React, { useCallback, useEffect } from "react";
import { StringParam } from "use-query-params";

import { Separator } from "@/components/ui/separator";
import Loader from "@/components/shared/Loader/Loader";
import { DateRangeSerializedValue } from "@/components/shared/DateRangeSelect";
import MetricDateRangeSelect from "@/components/pages-shared/traces/MetricDateRangeSelect/MetricDateRangeSelect";
import DashboardSaveActions from "@/components/pages-shared/dashboards/DashboardSaveActions/DashboardSaveActions";
import DashboardContent from "@/components/pages-shared/dashboards/DashboardContent/DashboardContent";
import DashboardSelectBox from "@/components/pages-shared/dashboards/DashboardSelectBox/DashboardSelectBox";
import { useMetricDateRangeCore } from "@/components/pages-shared/traces/MetricDateRangeSelect/useMetricDateRangeCore";
import useQueryParamAndLocalStorageState from "@/hooks/useQueryParamAndLocalStorageState";
import { useDashboardLifecycle } from "@/components/pages-shared/dashboards/hooks/useDashboardLifecycle";
import {
  useDashboardStore,
  selectSetConfig,
  selectConfig,
  selectSetRuntimeConfig,
} from "@/store/DashboardStore";
import { DEFAULT_DATE_PRESET } from "@/components/pages-shared/traces/MetricDateRangeSelect/constants";

const DASHBOARD_QUERY_PARAM_KEY = "dashboardId";
const DASHBOARD_LOCAL_STORAGE_KEY_PREFIX = "opik-project-dashboard";

interface DashboardsTabProps {
  projectId: string;
}

const DashboardsTab: React.FunctionComponent<DashboardsTabProps> = ({
  projectId,
}) => {
  const [dashboardId, setDashboardId] = useQueryParamAndLocalStorageState({
    localStorageKey: DASHBOARD_LOCAL_STORAGE_KEY_PREFIX,
    queryKey: DASHBOARD_QUERY_PARAM_KEY,
    defaultValue: null as string | null,
    queryParamConfig: StringParam,
    syncQueryWithLocalStorageOnInit: true,
  });

  const { dashboard, isPending, hasUnsavedChanges, save, discard } =
    useDashboardLifecycle({
      dashboardId: dashboardId || null,
      enabled: Boolean(dashboardId),
    });

  const config = useDashboardStore(selectConfig);
  const setConfig = useDashboardStore(selectSetConfig);
  const setRuntimeConfig = useDashboardStore(selectSetRuntimeConfig);

  useEffect(() => {
    setRuntimeConfig({ projectIds: [projectId] });
  }, [projectId, setRuntimeConfig]);

  const dateRangeValue = config?.dateRange || DEFAULT_DATE_PRESET;

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

  return (
    <div className="flex h-full flex-col px-6 pb-6">
      <div className="flex items-center justify-between gap-4 pb-6">
        <DashboardSelectBox
          value={dashboardId || null}
          onChange={setDashboardId}
          buttonClassName="w-[300px]"
        />

        <div className="flex shrink-0 items-center gap-2">
          {dashboard && (
            <DashboardSaveActions
              hasUnsavedChanges={hasUnsavedChanges}
              onSave={save}
              onDiscard={discard}
              dashboard={dashboard}
            />
          )}
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
        </div>
      </div>

      {isPending && <Loader />}

      {!isPending && !dashboardId && (
        <div className="flex h-full items-center justify-center">
          <p className="text-muted-foreground">
            No dashboard selected. Please select or create a dashboard.
          </p>
        </div>
      )}

      {!isPending && dashboardId && !dashboard && (
        <div className="flex h-full items-center justify-center">
          <p className="text-muted-foreground">Dashboard not found</p>
        </div>
      )}

      {!isPending && dashboard && (
        <DashboardContent hasUnsavedChanges={hasUnsavedChanges} />
      )}
    </div>
  );
};

export default DashboardsTab;
