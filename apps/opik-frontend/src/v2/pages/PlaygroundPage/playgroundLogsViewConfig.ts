import {
  DEFAULT_TRACE_LOGS_VIEW_CONFIG,
  TraceLogsViewConfig,
} from "@/v2/pages-shared/traces/TraceLogsView/TraceLogsView";

// Keeps the shared storage namespace on purpose: a separate one would drop the columns people
// already picked in this panel.
export const PLAYGROUND_LOGS_VIEW_CONFIG: TraceLogsViewConfig = {
  ...DEFAULT_TRACE_LOGS_VIEW_CONFIG,
  emptyStateTitle: "No logs yet",
  emptyStateDescription: "Traces will appear here once you run a prompt.",
};
