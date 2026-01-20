import React, { useCallback, useEffect } from "react";
import { StringParam } from "use-query-params";

import Loader from "@/components/shared/Loader/Loader";
import { DateRangeSerializedValue } from "@/components/shared/DateRangeSelect";
import MetricDateRangeSelect from "@/components/pages-shared/traces/MetricDateRangeSelect/MetricDateRangeSelect";
import DashboardSaveActions from "@/components/pages-shared/dashboards/DashboardSaveActions/DashboardSaveActions";
import DashboardContent from "@/components/pages-shared/dashboards/DashboardContent/DashboardContent";
import DashboardSelectBox from "@/components/pages-shared/dashboards/DashboardSelectBox/DashboardSelectBox";
import ShareDashboardButton from "@/components/pages-shared/dashboards/ShareDashboardButton/ShareDashboardButton";
import DashboardConfigButton from "@/components/pages-shared/dashboards/DashboardConfigButton/DashboardConfigButton";
import { useMetricDateRangeCore } from "@/components/pages-shared/traces/MetricDateRangeSelect/useMetricDateRangeCore";
import useQueryParamAndLocalStorageState from "@/hooks/useQueryParamAndLocalStorageState";
import { useDashboardLifecycle } from "@/components/pages-shared/dashboards/hooks/useDashboardLifecycle";
import {
  useDashboardStore,
  selectSetConfig,
  selectConfig,
  selectSetRuntimeConfig,
  selectHasUnsavedChanges,
} from "@/store/DashboardStore";
import { DEFAULT_DATE_PRESET } from "@/components/pages-shared/traces/MetricDateRangeSelect/constants";
import PageBodyStickyContainer from "@/components/layout/PageBodyStickyContainer/PageBodyStickyContainer";
import { PROJECT_TEMPLATE_LIST } from "@/lib/dashboard/templates";
import { Separator } from "@/components/ui/separator";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";

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

  useEffect(() => {
    if (!dashboardId) {
      setDashboardId(PROJECT_TEMPLATE_LIST[0].id);
    }
  }, [dashboardId, setDashboardId]);

  const { dashboard, isPending, save, discard, isTemplate } =
    useDashboardLifecycle({
      dashboardId: dashboardId || null,
      enabled: Boolean(dashboardId),
    });

  const hasUnsavedChanges = useDashboardStore(selectHasUnsavedChanges);

  const config = useDashboardStore(selectConfig);
  const setConfig = useDashboardStore(selectSetConfig);
  const setRuntimeConfig = useDashboardStore(selectSetRuntimeConfig);

  useEffect(() => {
    setRuntimeConfig({ projectIds: [projectId] });

    return () => {
      setRuntimeConfig({});
    };
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

  const handleDashboardCreated = useCallback(
    (newDashboardId: string) => {
      setDashboardId(newDashboardId);
    },
    [setDashboardId],
  );

  const handleDashboardDeleted = useCallback(
    (deletedDashboardId: string) => {
      if (dashboardId === deletedDashboardId) {
        setDashboardId(PROJECT_TEMPLATE_LIST[0]?.id || null);
      }
    },
    [dashboardId, setDashboardId],
  );

  const dashboardSelectBox = (
    <DashboardSelectBox
      value={dashboardId || null}
      onChange={setDashboardId}
      buttonClassName="w-[300px]"
      onDashboardCreated={handleDashboardCreated}
      onDashboardDeleted={handleDashboardDeleted}
      defaultProjectId={projectId}
      disabled={hasUnsavedChanges}
      templates={PROJECT_TEMPLATE_LIST}
    />
  );

  return (
    <>
      <PageBodyStickyContainer
        className="flex items-center justify-between gap-4 pb-3 pt-2"
        direction="bidirectional"
        limitWidth
      >
        {hasUnsavedChanges ? (
          <TooltipWrapper content="Save or discard your changes before switching">
            <div>{dashboardSelectBox}</div>
          </TooltipWrapper>
        ) : (
          dashboardSelectBox
        )}

        <div className="flex shrink-0 items-center gap-2">
          {dashboard && (
            <DashboardSaveActions
              onSave={save}
              onDiscard={discard}
              dashboard={dashboard}
              isTemplate={isTemplate}
              navigateOnCreate={false}
              onDashboardCreated={handleDashboardCreated}
              defaultProjectId={projectId}
              defaultExperimentIds={config?.experimentIds}
            />
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
          <DashboardConfigButton disableProjectSelector />
        </div>
      </PageBodyStickyContainer>

      <div className="px-6 pb-4 pt-1">
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
            <p className="text-muted-foreground">
              Dashboard could not be loaded. Please select another dashboard
              from the dropdown.
            </p>
          </div>
        )}

        {!isPending && dashboard && <DashboardContent />}
      </div>
    </>
  );
};

export default DashboardsTab;
