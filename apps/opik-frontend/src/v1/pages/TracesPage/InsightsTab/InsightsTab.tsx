import React, { useCallback, useEffect } from "react";
import { StringParam } from "use-query-params";

import Loader from "@/shared/Loader/Loader";
import {
  useMetricDateRangeWithQueryAndStorage,
  MetricDateRangeSelect,
} from "@/v1/pages-shared/traces/MetricDateRangeSelect";
import DashboardContent from "@/v1/pages-shared/dashboards/DashboardContent/DashboardContent";
import DashboardAutoSaveIndicator from "@/v1/pages-shared/dashboards/DashboardAutoSaveIndicator/DashboardAutoSaveIndicator";
import InsightsViewSelector from "@/v1/pages/TracesPage/InsightsTab/InsightsViewSelector";
import ShareDashboardButton from "@/v1/pages-shared/dashboards/ShareDashboardButton/ShareDashboardButton";
import useQueryParamAndLocalStorageState from "@/hooks/useQueryParamAndLocalStorageState";
import { useDashboardLifecycle } from "@/v1/pages-shared/dashboards/hooks/useDashboardLifecycle";
import {
  useDashboardStore,
  selectSetRuntimeConfig,
} from "@/store/DashboardStore";
import PageBodyStickyContainer from "@/v1/layout/PageBodyStickyContainer/PageBodyStickyContainer";
import {
  PROJECT_TEMPLATE_LIST,
  DEPRECATED_PROJECT_METRICS_ID,
  DEPRECATED_PROJECT_PERFORMANCE_ID,
} from "@/lib/dashboard/templates";
import { Separator } from "@/ui/separator";
import { useActiveWorkspaceName } from "@/store/AppStore";

const DASHBOARD_QUERY_PARAM_KEY = "dashboardId";
const DASHBOARD_LOCAL_STORAGE_KEY_PREFIX = "opik-project-dashboard";

interface InsightsTabProps {
  projectId: string;
}

const DEFAULT_TEMPLATE = PROJECT_TEMPLATE_LIST[0];
const DEFAULT_TEMPLATE_ID = DEFAULT_TEMPLATE.id;

const InsightsTab: React.FunctionComponent<InsightsTabProps> = ({
  projectId,
}) => {
  const workspaceName = useActiveWorkspaceName();

  const [dashboardId, setDashboardId] = useQueryParamAndLocalStorageState({
    localStorageKey: `${DASHBOARD_LOCAL_STORAGE_KEY_PREFIX}-${workspaceName}`,
    queryKey: DASHBOARD_QUERY_PARAM_KEY,
    defaultValue: null as string | null,
    queryParamConfig: StringParam,
    syncQueryWithLocalStorageOnInit: true,
    syncLocalStorageAcrossTabs: false,
  });

  // Ensure a valid dashboard is always selected:
  // - no permission → lock to default template
  // - no selection or deprecated ID → fall back to default template
  useEffect(() => {
    const needsDefault =
      !dashboardId ||
      dashboardId === DEPRECATED_PROJECT_METRICS_ID ||
      dashboardId === DEPRECATED_PROJECT_PERFORMANCE_ID;

    if (needsDefault && dashboardId !== DEFAULT_TEMPLATE_ID) {
      setDashboardId(DEFAULT_TEMPLATE_ID);
    }
  }, [dashboardId, setDashboardId]);

  const { dashboard, isPending, saveStatus } = useDashboardLifecycle({
    dashboardId: dashboardId || null,
    enabled: Boolean(dashboardId),
  });

  const setRuntimeConfig = useDashboardStore(selectSetRuntimeConfig);

  const { dateRange, handleDateRangeChange, minDate, maxDate, dateRangeValue } =
    useMetricDateRangeWithQueryAndStorage({
      key: "dashboard_time_range",
      localStorageKey: "opik-project-insights-daterange",
    });

  useEffect(() => {
    setRuntimeConfig({
      projectIds: [projectId],
      dateRange: dateRangeValue,
      dashboardType: dashboard?.type,
    });

    return () => {
      setRuntimeConfig({});
    };
  }, [projectId, dateRangeValue, dashboard?.type, setRuntimeConfig]);

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

  return (
    <>
      <PageBodyStickyContainer
        className="flex flex-wrap items-center justify-between gap-x-8 gap-y-2 pb-3 pt-2"
        direction="bidirectional"
        limitWidth
      >
        <InsightsViewSelector
          value={dashboardId || null}
          onChange={setDashboardId}
          onViewCreated={handleDashboardCreated}
          onViewDeleted={handleDashboardDeleted}
        />
        <div className="flex shrink-0 items-center gap-2">
          <DashboardAutoSaveIndicator saveStatus={saveStatus} />
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
      </PageBodyStickyContainer>

      <div className="px-6 pb-4 pt-1">
        {isPending && <Loader />}

        {!isPending && !dashboardId && (
          <div className="flex h-full items-center justify-center">
            <p className="text-muted-foreground">
              No view selected. Please select or create a view.
            </p>
          </div>
        )}

        {!isPending && dashboardId && !dashboard && (
          <div className="flex h-full items-center justify-center">
            <p className="text-muted-foreground">
              View could not be loaded. Please select another view from the
              dropdown.
            </p>
          </div>
        )}

        {!isPending && dashboard && <DashboardContent />}
      </div>
    </>
  );
};

export default InsightsTab;
