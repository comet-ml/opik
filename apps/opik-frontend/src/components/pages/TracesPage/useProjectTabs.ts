import { useMemo, useCallback } from "react";
import { StringParam, useQueryParam } from "use-query-params";
import { LOGS_TYPE } from "@/constants/traces";

export enum PROJECT_TAB {
  logs = "logs",
  metrics = "metrics",
  evaluators = "rules",
  annotationQueues = "annotation-queues",
}

const DEFAULT_TAB = PROJECT_TAB.logs;
const DEFAULT_LOGS_TYPE = LOGS_TYPE.traces;

export const isProjectTab = (
  value: string | null | undefined,
): value is PROJECT_TAB =>
  Object.values(PROJECT_TAB).includes(value as PROJECT_TAB);

export const isLogsType = (
  value: string | null | undefined,
): value is LOGS_TYPE => Object.values(LOGS_TYPE).includes(value as LOGS_TYPE);

const QUERY_PARAM_OPTIONS = { updateType: "replaceIn" as const };

type UseProjectTabsOptions = {
  defaultLogsType?: LOGS_TYPE;
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
 */
const useProjectTabs = (options?: UseProjectTabsOptions) => {
  const resolvedDefaultLogsType = options?.defaultLogsType ?? DEFAULT_LOGS_TYPE;
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

  // Legacy query param (read-only for migration)
  const [legacyType, setLegacyType] = useQueryParam(
    "type",
    StringParam,
    QUERY_PARAM_OPTIONS,
  );

  // Compute effective values: new params take precedence, fall back to legacy
  const { activeTab, logsType } = useMemo(() => {
    // If new params exist, use them
    if (tabParam || logsTypeParam) {
      return {
        activeTab: isProjectTab(tabParam) ? tabParam : DEFAULT_TAB,
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

    if (isProjectTab(legacyType)) {
      // ?type=metrics → Metrics tab, default logsType
      return { activeTab: legacyType, logsType: resolvedDefaultLogsType };
    }

    // No params at all → defaults
    return { activeTab: DEFAULT_TAB, logsType: resolvedDefaultLogsType };
  }, [tabParam, logsTypeParam, legacyType, resolvedDefaultLogsType]);

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
      // Ensure tab is set to logs when changing logs type
      if (tabParam !== PROJECT_TAB.logs) {
        setTabParam(PROJECT_TAB.logs);
      }
      clearLegacy();
    },
    [setLogsTypeParam, setTabParam, tabParam, clearLegacy],
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

  return {
    activeTab,
    logsType,
    setActiveTab,
    setLogsType,
    handleTabChange,
  };
};

export default useProjectTabs;
