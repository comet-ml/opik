import React, { useCallback, useEffect } from "react";
import { StringParam } from "use-query-params";

import Loader from "@/shared/Loader/Loader";
import {
  useMetricDateRangeWithQueryAndStorage,
  MetricDateRangeSelect,
} from "@/v2/pages-shared/traces/MetricDateRangeSelect";
import {
  resolveProjectDateRangeConfig,
  ProjectDateRangeConfig,
} from "@/v2/pages-shared/traces/resolveProjectDateRangeConfig";
import useProjectById from "@/api/projects/useProjectById";
import DashboardContent from "@/v2/pages-shared/dashboards/DashboardContent/DashboardContent";
import DashboardAutoSaveIndicator from "@/v2/pages-shared/dashboards/DashboardAutoSaveIndicator/DashboardAutoSaveIndicator";
import ProjectDashboardViewSelector from "@/v2/pages/ProjectDashboardsPage/ProjectDashboardViewSelector";
import ShareDashboardButton from "@/v2/pages-shared/dashboards/ShareDashboardButton/ShareDashboardButton";
import useQueryParamAndLocalStorageState from "@/hooks/useQueryParamAndLocalStorageState";
import { useDashboardLifecycle } from "@/v2/pages-shared/dashboards/hooks/useDashboardLifecycle";
import { DASHBOARD_SCOPE } from "@/types/dashboard";
import {
  useDashboardStore,
  selectSetRuntimeConfig,
} from "@/store/DashboardStore";
import PageBodyScrollContainer from "@/v2/layout/PageBodyScrollContainer/PageBodyScrollContainer";
import PageBodyStickyContainer from "@/shared/PageBodyStickyContainer/PageBodyStickyContainer";
import {
  PROJECT_TEMPLATE_LIST,
  DEPRECATED_PROJECT_METRICS_ID,
  DEPRECATED_PROJECT_PERFORMANCE_ID,
} from "@/lib/dashboard/templates";
import { Separator } from "@/ui/separator";
import { useActiveWorkspaceName } from "@/store/AppStore";
import { useActiveProjectId } from "@/store/AppStore";
import { usePermissions } from "@/contexts/PermissionsContext";

const DASHBOARD_QUERY_PARAM_KEY = "dashboardId";
const DASHBOARD_LOCAL_STORAGE_KEY_PREFIX = "opik-project-dashboard";

const DEFAULT_TEMPLATE = PROJECT_TEMPLATE_LIST[0];
const DEFAULT_TEMPLATE_ID = DEFAULT_TEMPLATE.id;

type ProjectDashboardsContentProps = {
  projectId: string;
  dateRangeConfig: ProjectDateRangeConfig;
};

const ProjectDashboardsContent: React.FunctionComponent<
  ProjectDashboardsContentProps
> = ({ projectId, dateRangeConfig }) => {
  const workspaceName = useActiveWorkspaceName();

  const {
    permissions: { canEditDashboards },
  } = usePermissions();

  const [dashboardId, setDashboardId] = useQueryParamAndLocalStorageState({
    localStorageKey: `${DASHBOARD_LOCAL_STORAGE_KEY_PREFIX}-${workspaceName}`,
    queryKey: DASHBOARD_QUERY_PARAM_KEY,
    defaultValue: null as string | null,
    queryParamConfig: StringParam,
    syncQueryWithLocalStorageOnInit: true,
    syncLocalStorageAcrossTabs: false,
  });

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
    scope: DASHBOARD_SCOPE.INSIGHTS,
    readOnly: !canEditDashboards,
  });

  useEffect(() => {
    if (!isPending && dashboardId && !dashboard) {
      setDashboardId(DEFAULT_TEMPLATE_ID);
    }
  }, [isPending, dashboardId, dashboard, setDashboardId]);

  const setRuntimeConfig = useDashboardStore(selectSetRuntimeConfig);

  const { dateRange, handleDateRangeChange, minDate, maxDate, dateRangeValue } =
    useMetricDateRangeWithQueryAndStorage({
      key: "dashboard_time_range",
      localStorageKey: "opik-project-insights-daterange",
      ...dateRangeConfig,
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
    <PageBodyScrollContainer>
      <PageBodyStickyContainer
        className="mb-4 mt-6 flex items-center justify-between"
        direction="horizontal"
      >
        <h1 className="comet-body-accented truncate break-words">Dashboards</h1>
      </PageBodyStickyContainer>
      <PageBodyStickyContainer
        className="flex flex-wrap items-center justify-between gap-x-8 gap-y-2 pb-3"
        direction="bidirectional"
        limitWidth
      >
        <ProjectDashboardViewSelector
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
        {!isPending && dashboard && <DashboardContent />}
      </div>
    </PageBodyScrollContainer>
  );
};

/**
 * Resolves the project before mounting the content.
 *
 * The date-range state below is backed by use-local-storage-state, which captures its
 * `defaultValue` once (useState) and writes that captured value into storage. A content mount that
 * happened while the project name was still unknown would therefore freeze the workspace 30-day
 * default and keep it after the demo project's 24h default arrived. Gating the mount makes the
 * captured value right by construction. A failed lookup reports not-pending, so this cannot hang.
 */
const ProjectDashboardsPage: React.FunctionComponent = () => {
  const projectId = useActiveProjectId()!;
  const { data: project, isPending: isProjectPending } = useProjectById(
    { projectId },
    { refetchOnMount: false },
  );

  if (isProjectPending) {
    return <Loader />;
  }

  return (
    <ProjectDashboardsContent
      projectId={projectId}
      dateRangeConfig={resolveProjectDateRangeConfig(project?.name)}
    />
  );
};

export default ProjectDashboardsPage;
