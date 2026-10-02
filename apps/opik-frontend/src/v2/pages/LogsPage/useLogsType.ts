import { useMemo, useCallback, useEffect, useState } from "react";
import { StringParam, useQueryParam } from "use-query-params";
import useLocalStorageState from "use-local-storage-state";
import useThreadsStatistic from "@/api/traces/useThreadsStatistic";
import { IntervalWindow } from "@/v2/pages-shared/traces/MetricDateRangeSelect";
import { LOGS_TYPE } from "@/constants/traces";
import { ProjectDateRangeConfig } from "@/v2/pages-shared/traces/resolveProjectDateRangeConfig";
import { LOGS_SOURCE } from "@/types/traces";
import useLogsIntervalWindow from "@/v2/pages/LogsPage/useLogsIntervalWindow";
import { STATISTIC_AGGREGATION_TYPE } from "@/types/shared";

const isLogsType = (value: string | null | undefined): value is LOGS_TYPE =>
  Object.values(LOGS_TYPE).includes(value as LOGS_TYPE);

const QUERY_PARAM_OPTIONS = { updateType: "replaceIn" as const };

type UseLogsTypeOptions = {
  projectId: string;
  /**
   * Resolved by LogsPage from the project query it already owns, and shared with the tabs. All three
   * read one date-range key, so they must be given the same values.
   */
  dateRangeConfig: ProjectDateRangeConfig;
  intervalWindow?: IntervalWindow;
};

/**
 * logsType priority: URL ?logsType= > legacy ?type= > localStorage > smart default (threadCount) > traces
 * threadCount=undefined means stats are still loading.
 */
const useLogsType = (options: UseLogsTypeOptions) => {
  const { projectId, dateRangeConfig, intervalWindow } = options;

  const ownIntervalWindow = useLogsIntervalWindow(dateRangeConfig, false);
  const { selectionKey, intervalStart, intervalEnd } =
    intervalWindow ?? ownIntervalWindow;

  const [probeWindow, setProbeWindow] = useState({
    projectId,
    selectionKey,
    intervalStart,
    intervalEnd,
  });
  if (
    probeWindow.projectId !== projectId ||
    probeWindow.selectionKey !== selectionKey
  ) {
    setProbeWindow({ projectId, selectionKey, intervalStart, intervalEnd });
  }

  const [firstAnswer, setFirstAnswer] = useState<{
    projectId: string;
    logsType: LOGS_TYPE;
  }>();
  const answeredLogsType =
    firstAnswer?.projectId === projectId ? firstAnswer.logsType : undefined;

  const [storedLogsType, setStoredLogsType] = useLocalStorageState<LOGS_TYPE>(
    `project-logsType-${projectId}`,
  );

  const [logsTypeParam, setLogsTypeParam] = useQueryParam(
    "logsType",
    StringParam,
    QUERY_PARAM_OPTIONS,
  );

  const [legacyType, setLegacyType] = useQueryParam(
    "type",
    StringParam,
    QUERY_PARAM_OPTIONS,
  );

  const hasChosenLogsType =
    isLogsType(logsTypeParam) ||
    isLogsType(legacyType) ||
    isLogsType(storedLogsType);

  const { data: threadsStats, isError: isStatsError } = useThreadsStatistic(
    {
      projectId,
      fromTime: probeWindow.intervalStart,
      toTime: probeWindow.intervalEnd,
      logsSource: LOGS_SOURCE.sdk,
    },
    {
      enabled: !!projectId && !hasChosenLogsType && !answeredLogsType,
      refetchOnMount: false,
    },
  );

  const threadCount = useMemo(() => {
    if (isStatsError) return 0;
    if (!threadsStats) return undefined;

    const threadCountStat = threadsStats.stats?.find(
      (stat) =>
        stat.name === "thread_count" &&
        stat.type === STATISTIC_AGGREGATION_TYPE.COUNT,
    );

    return threadCountStat?.type === STATISTIC_AGGREGATION_TYPE.COUNT
      ? threadCountStat.value
      : 0;
  }, [threadsStats, isStatsError]);

  const probedLogsType =
    threadCount === undefined
      ? undefined
      : threadCount > 0
        ? LOGS_TYPE.threads
        : LOGS_TYPE.traces;
  if (!answeredLogsType && probedLogsType) {
    setFirstAnswer({ projectId, logsType: probedLogsType });
  }
  const defaultLogsType = answeredLogsType ?? probedLogsType;

  // One-time legacy migration: ?type=traces → ?logsType=traces
  useEffect(() => {
    if (isLogsType(legacyType) && !logsTypeParam) {
      setLogsTypeParam(legacyType);
      setLegacyType(undefined);
    }
  }, [legacyType, logsTypeParam, setLogsTypeParam, setLegacyType]);

  const logsType = useMemo(() => {
    const resolvedDefault = isLogsType(storedLogsType)
      ? storedLogsType
      : defaultLogsType ?? LOGS_TYPE.traces;

    if (isLogsType(logsTypeParam)) {
      return logsTypeParam;
    }

    if (isLogsType(legacyType)) {
      return legacyType;
    }

    return resolvedDefault;
  }, [logsTypeParam, legacyType, storedLogsType, defaultLogsType]);

  const setLogsType = useCallback(
    (newLogsType: LOGS_TYPE) => {
      setLogsTypeParam(newLogsType);
      if (legacyType) {
        setLegacyType(undefined);
      }
      setStoredLogsType(newLogsType);
    },
    [setLogsTypeParam, legacyType, setLegacyType, setStoredLogsType],
  );

  const needsDefaultResolution =
    !logsTypeParam &&
    !legacyType &&
    !isLogsType(storedLogsType) &&
    defaultLogsType === undefined;

  return {
    logsType,
    needsDefaultResolution,
    setLogsType,
  };
};

export default useLogsType;
