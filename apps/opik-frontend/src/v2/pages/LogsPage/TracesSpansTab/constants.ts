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

export const getLogsFiltersUrlKey = (type: TRACE_DATA_TYPE) =>
  `${type}_filters`;

export const getLogsFiltersMemoryKey = (projectId: string, urlKey: string) =>
  `logs-filters:${projectId}:${urlKey}`;

export const getLogsEnvironmentMemoryKey = (projectId: string) =>
  `logs-environment:${projectId}`;
