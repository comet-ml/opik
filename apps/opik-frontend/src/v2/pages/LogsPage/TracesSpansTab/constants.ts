import { TRACE_DATA_TYPE } from "@/constants/traces";
import { TRACE_DEFAULT_PINNED_CHIPS } from "@/v2/pages-shared/traces/traceChipDefinitions";

export const LOGS_TABLE_ID: Record<TRACE_DATA_TYPE, string> = {
  [TRACE_DATA_TYPE.traces]: "logs.traces",
  [TRACE_DATA_TYPE.spans]: "logs.spans",
};

export const LOGS_DEFAULT_PINNED_CHIPS: Record<TRACE_DATA_TYPE, string[]> = {
  [TRACE_DATA_TYPE.traces]: TRACE_DEFAULT_PINNED_CHIPS,
  [TRACE_DATA_TYPE.spans]: ["type", "tags", "with_errors", "metadata"],
};

export const THREADS_FILTERS_URL_KEY = "threads_filters";

export const getLogsFiltersUrlKey = (type: TRACE_DATA_TYPE) =>
  `${type}_filters`;

export const getLogsFiltersMemoryKey = (
  userName: string,
  projectId: string,
  urlKey: string,
) => `logs-filters:${userName}:${projectId}:${urlKey}`;

export const getLogsEnvironmentMemoryKey = (
  userName: string,
  projectId: string,
) => `logs-environment:${userName}:${projectId}`;
