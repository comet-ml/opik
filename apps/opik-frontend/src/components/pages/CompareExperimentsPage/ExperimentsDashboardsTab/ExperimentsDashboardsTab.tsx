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
import { EXPERIMENTS_TEMPLATE_LIST } from "@/lib/dashboard/templates";
import { EXPERIMENT_DATA_SOURCE } from "@/types/dashboard";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import CompareExperimentsButton from "@/components/pages/CompareExperimentsPage/CompareExperimentsButton/CompareExperimentsButton";
import { Separator } from "@/components/ui/separator";

const DASHBOARD_QUERY_PARAM_KEY = "dashboardId";
const DASHBOARD_LOCAL_STORAGE_KEY_PREFIX = "opik-experiments-dashboard";

interface ExperimentsDashboardsTabProps {
  experimentsIds: string[];
}

const ExperimentsDashboardsTab: React.FunctionComponent<
  ExperimentsDashboardsTabProps
> = ({ experimentsIds }) => {
  const [dashboardId, setDashboardId] = useQueryParamAndLocalStorageState({
    localStorageKey: DASHBOARD_LOCAL_STORAGE_KEY_PREFIX,
    queryKey: DASHBOARD_QUERY_PARAM_KEY,
    defaultValue: null as string | null,
    queryParamConfig: StringParam,
    syncQueryWithLocalStorageOnInit: true,
  });

  useEffect(() => {
    if (!dashboardId && EXPERIMENTS_TEMPLATE_LIST.length > 0) {
      setDashboardId(EXPERIMENTS_TEMPLATE_LIST[0].id);
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
    setRuntimeConfig({
      experimentIds: experimentsIds,
      experimentDataSource: EXPERIMENT_DATA_SOURCE.SELECT_EXPERIMENTS,
    });

    return () => {
      setRuntimeConfig({});
    };
  }, [experimentsIds, setRuntimeConfig]);

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
        setDashboardId(EXPERIMENTS_TEMPLATE_LIST[0]?.id || null);
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
      defaultExperimentIds={experimentsIds}
      disabled={hasUnsavedChanges}
      templates={EXPERIMENTS_TEMPLATE_LIST}
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
          <CompareExperimentsButton
            variant="outline"
            tooltipContent="Select experiments to compare"
          />
          <Separator orientation="vertical" className="mx-2 h-4" />
          {dashboard && (
            <DashboardSaveActions
              onSave={save}
              onDiscard={discard}
              dashboard={dashboard}
              isTemplate={isTemplate}
              navigateOnCreate={false}
              onDashboardCreated={handleDashboardCreated}
              defaultProjectId={config?.projectIds?.[0]}
              defaultExperimentIds={experimentsIds}
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
          <DashboardConfigButton disableExperimentsSelector />
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

export default ExperimentsDashboardsTab;
