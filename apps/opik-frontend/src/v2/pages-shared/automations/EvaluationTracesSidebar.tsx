import React from "react";

import TraceLogsSidebar, {
  TraceLogsViewConfig,
} from "@/v2/pages-shared/traces/TraceLogsSidebar/TraceLogsSidebar";
import { useTraceLogsSidebarControls } from "@/v2/pages-shared/traces/TraceLogsSidebar/useTraceLogsSidebarControls";
import { LOGS_SOURCE, TRACE_VISIBILITY_MODE } from "@/types/traces";

// Evaluation-traces variant of the logs sidebar: a KPI dashboard on top, hidden-visibility
// monitoring traces, isolated column state, and a trimmed default column set (tags / comments /
// feedback-score columns add no signal for LLM-judge monitoring traces, but stay selectable).
export const EVALUATION_TRACES_VIEW_CONFIG: TraceLogsViewConfig = {
  storageNamespace: "eval-",
  defaultColumns: [
    "start_time",
    "input",
    "output",
    "error_info",
    "duration",
    "usage.total_tokens",
    "total_estimated_cost",
  ],
  autoSelectScoreColumns: false,
  showMetricsSummary: true,
  visibilityMode: TRACE_VISIBILITY_MODE.hidden,
};

/**
 * A single page-level sidebar for the online-evaluation rules table. Each rule row renders only a
 * trigger (writing the rule's metadata.rule_id into the shared tls_* params); this one instance
 * reads those params and displays the matching evaluator traces — so pagination/sort/filters aren't
 * raced across one-sidebar-per-row.
 */
export const EvaluationTracesSidebar: React.FunctionComponent<{
  projectId: string;
}> = ({ projectId }) => {
  const { open, closeSidebar } = useTraceLogsSidebarControls();

  return (
    <TraceLogsSidebar
      open={open}
      onClose={closeSidebar}
      projectId={projectId}
      logsSource={LOGS_SOURCE.evaluator}
      title="Evaluation traces"
      viewConfig={EVALUATION_TRACES_VIEW_CONFIG}
    />
  );
};
