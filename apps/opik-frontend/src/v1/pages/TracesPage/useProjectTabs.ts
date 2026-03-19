import { useMemo, useCallback, useEffect } from "react";
import { StringParam, useQueryParam } from "use-query-params";
import useLocalStorageState from "use-local-storage-state";
import useThreadsStatistic from "@/api/traces/useThreadsStatistic";
import { useMetricDateRangeWithQueryAndStorage } from "@/v1/pages-shared/traces/MetricDateRangeSelect";
import { LOGS_TYPE, PROJECT_TAB } from "@/constants/traces";
import { STATISTIC_AGGREGATION_TYPE } from "@/types/shared";

const DEFAULT_TAB = PROJECT_TAB.logs;

export const isProjectTab = (
  value: string | null | undefined,
): value is PROJECT_TAB =>
  Object.values(PROJECT_TAB).includes(value as PROJECT_TAB);

export const isLogsType = (
  value: string | null | undefined,
): value is LOGS_TYPE => Object.values(LOGS_TYPE).includes(value as LOGS_TYPE);

const QUERY_PARAM_OPTIONS = { updateType: "replaceIn" as const };

type UseProjectTabsOptions = {
  projectId: string;
};

/**
 * Manages TracesPage URL params with backward compatibility.
 *
 * New format: ?tab=logs&logsType=traces
 * Legacy format: ?type=traces (single param for both tab and logs type)
 *
 * If new params are present, they take precedence.
 * If only the legacy `type` param is present, it is used to compute
 * activeTab and logsType for backward compatibility.
 *
 * logsType priority: URL params > localStorage > smart default (threadCount) > fallback (traces)
 * threadCount=undefined means stats are still loading.
 */
const useProjectTabs = (options: UseProjectTabsOptions) => {
  const { projectId } = options;

  const { intervalStart, intervalEnd } =
    useMetricDateRangeWithQueryAndStorage();

  const { data: threadsStats } = useThreadsStatistic(
    {
      projectId,
      fromTime: intervalStart,
      toTime: intervalEnd,
    },
    {
      enabled: !!projectId,
      refetchOnMount: false,
    },
  );

  const threadCount = useMemo(() => {
    if (!threadsStats) return undefined;

    const threadCountStat = threadsStats.stats?.find(
      (stat) =>
        stat.name === "thread_count" &&
        stat.type === STATISTIC_AGGREGATION_TYPE.COUNT,
    );

    return threadCountStat?.type === STATISTIC_AGGREGATION_TYPE.COUNT
      ? threadCountStat.value
      : 0;
  }, [threadsStats]);

  const [storedLogsType, setStoredLogsType] = useLocalStorageState<LOGS_TYPE>(
    `project-logsType-${projectId}`,
  );

  // New query params
  const [tabParam, setTabParam] = useQueryParam(
    "tab",
    StringParam,
    QUERY_PARAM_OPTIONS,
  );
  const [logsTypeParam, setLogsTypeParam] = useQueryParam(
    "logsType",
    StringParam,
    QUERY_PARAM_OPTIONS,
  );

  // Legacy query params (read-only for migration)
  const [legacyType, setLegacyType] = useQueryParam(
    "type",
    StringParam,
    QUERY_PARAM_OPTIONS,
  );
  const [legacyView, setLegacyView] = useQueryParam(
    "view",
    StringParam,
    QUERY_PARAM_OPTIONS,
  );

  // One-time cleanup: clear stale ?view= param from old bookmarks
  useEffect(() => {
    if (legacyView) {
      setLegacyView(undefined);
    }
  }, [legacyView, setLegacyView]);

  // Compute effective values: new params take precedence, fall back to legacy
  const { activeTab, logsType } = useMemo(() => {
    const defaultLogsType =
      threadCount !== undefined && threadCount > 0
        ? LOGS_TYPE.threads
        : LOGS_TYPE.traces;

    const resolvedDefaultLogsType = isLogsType(storedLogsType)
      ? storedLogsType
      : defaultLogsType;

    // If new params exist, use them
    if (tabParam || logsTypeParam) {
      // Map legacy "metrics" tab value to "insights"
      const resolvedTab =
        tabParam === "metrics" ? PROJECT_TAB.insights : tabParam;
      return {
        activeTab: isProjectTab(resolvedTab) ? resolvedTab : DEFAULT_TAB,
        logsType: isLogsType(logsTypeParam)
          ? logsTypeParam
          : resolvedDefaultLogsType,
      };
    }

    // Otherwise, compute from legacy `type` param
    if (isLogsType(legacyType)) {
      // ?type=traces → Logs tab with traces
      return { activeTab: PROJECT_TAB.logs, logsType: legacyType };
    }

    // Map legacy ?type=metrics to insights
    if (legacyType === "metrics") {
      return {
        activeTab: PROJECT_TAB.insights,
        logsType: resolvedDefaultLogsType,
      };
    }

    if (isProjectTab(legacyType)) {
      return { activeTab: legacyType, logsType: resolvedDefaultLogsType };
    }

    // If old ?view=dashboards bookmark, land on insights
    if (legacyView === "dashboards") {
      return {
        activeTab: PROJECT_TAB.insights,
        logsType: resolvedDefaultLogsType,
      };
    }

    // No params at all → defaults
    return { activeTab: DEFAULT_TAB, logsType: resolvedDefaultLogsType };
  }, [
    tabParam,
    logsTypeParam,
    legacyType,
    legacyView,
    storedLogsType,
    threadCount,
  ]);

  // Clear legacy param when writing new params
  const clearLegacy = useCallback(() => {
    if (legacyType) {
      setLegacyType(undefined);
    }
  }, [legacyType, setLegacyType]);

  const setActiveTab = useCallback(
    (newTab: PROJECT_TAB) => {
      setTabParam(newTab);
      clearLegacy();
    },
    [setTabParam, clearLegacy],
  );

  const setLogsType = useCallback(
    (newLogsType: LOGS_TYPE) => {
      setLogsTypeParam(newLogsType);
      if (tabParam !== PROJECT_TAB.logs) {
        setTabParam(PROJECT_TAB.logs);
      }
      clearLegacy();
      setStoredLogsType(newLogsType);
    },
    [setLogsTypeParam, setTabParam, tabParam, clearLegacy, setStoredLogsType],
  );

  // Combined handler for main tab change
  const handleTabChange = useCallback(
    (newTab: string) => {
      if (isProjectTab(newTab)) {
        setTabParam(newTab);
        clearLegacy();
      }
    },
    [setTabParam, clearLegacy],
  );

  const needsDefaultResolution =
    !tabParam &&
    !logsTypeParam &&
    !legacyType &&
    !isLogsType(storedLogsType) &&
    threadCount === undefined;

  return {
    activeTab,
    logsType,
    needsDefaultResolution,
    setActiveTab,
    setLogsType,
    handleTabChange,
  };
};

export default useProjectTabs;
