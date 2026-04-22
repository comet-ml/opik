import { useMemo, useCallback, useEffect } from "react";
import { StringParam, useQueryParam } from "use-query-params";
import useLocalStorageState from "use-local-storage-state";
import useThreadsStatistic from "@/api/traces/useThreadsStatistic";
import { useMetricDateRangeWithQueryAndStorage } from "@/v2/pages-shared/traces/MetricDateRangeSelect";
import { LOGS_TYPE } from "@/constants/traces";
import { LOGS_SOURCE } from "@/types/traces";
import { STATISTIC_AGGREGATION_TYPE } from "@/types/shared";

const isLogsType = (value: string | null | undefined): value is LOGS_TYPE =>
  Object.values(LOGS_TYPE).includes(value as LOGS_TYPE);

const QUERY_PARAM_OPTIONS = { updateType: "replaceIn" as const };

type UseLogsTypeOptions = {
  projectId: string;
};

/**
 * logsType priority: URL ?logsType= > legacy ?type= > localStorage > smart default (threadCount) > traces
 * threadCount=undefined means stats are still loading.
 */
const useLogsType = (options: UseLogsTypeOptions) => {
  const { projectId } = options;

  const { intervalStart, intervalEnd } =
    useMetricDateRangeWithQueryAndStorage();

  const { data: threadsStats, isError: isStatsError } = useThreadsStatistic(
    {
      projectId,
      fromTime: intervalStart,
      toTime: intervalEnd,
      logsSource: LOGS_SOURCE.sdk,
    },
    {
      enabled: !!projectId,
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

  // One-time legacy migration: ?type=traces → ?logsType=traces
  useEffect(() => {
    if (isLogsType(legacyType) && !logsTypeParam) {
      setLogsTypeParam(legacyType);
      setLegacyType(undefined);
    }
  }, [legacyType, logsTypeParam, setLogsTypeParam, setLegacyType]);

  const logsType = useMemo(() => {
    const defaultLogsType =
      threadCount !== undefined && threadCount > 0
        ? LOGS_TYPE.threads
        : LOGS_TYPE.traces;

    const resolvedDefault = isLogsType(storedLogsType)
      ? storedLogsType
      : defaultLogsType;

    if (isLogsType(logsTypeParam)) {
      return logsTypeParam;
    }

    if (isLogsType(legacyType)) {
      return legacyType;
    }

    return resolvedDefault;
  }, [logsTypeParam, legacyType, storedLogsType, threadCount]);

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
    threadCount === undefined;

  return {
    logsType,
    needsDefaultResolution,
    setLogsType,
  };
};

export default useLogsType;
