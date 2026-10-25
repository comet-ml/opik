import { COLUMN_EXPERIMENT_ID } from "@/types/shared";
import {
  DEFAULT_TRACE_LOGS_VIEW_CONFIG,
  TraceLogsViewConfig,
} from "@/v2/pages-shared/traces/TraceLogsView/TraceLogsView";

export const placeExperimentAfterOutput = (columns: string[]) => {
  const rest = columns.filter((id) => id !== COLUMN_EXPERIMENT_ID);
  const afterOutput = rest.indexOf("output") + 1;
  return [
    ...rest.slice(0, afterOutput),
    COLUMN_EXPERIMENT_ID,
    ...rest.slice(afterOutput),
  ];
};

// Keeps the shared storage namespace on purpose: a separate one would drop the columns people
// already picked in this panel. These defaults only reach people who never changed the columns.
export const PLAYGROUND_LOGS_VIEW_CONFIG: TraceLogsViewConfig = {
  ...DEFAULT_TRACE_LOGS_VIEW_CONFIG,
  defaultColumns: placeExperimentAfterOutput(
    DEFAULT_TRACE_LOGS_VIEW_CONFIG.defaultColumns,
  ),
  defaultColumnsOrder: placeExperimentAfterOutput(
    DEFAULT_TRACE_LOGS_VIEW_CONFIG.defaultColumnsOrder,
  ),
};
