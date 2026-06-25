import React from "react";
import { CellContext } from "@tanstack/react-table";

import CellWrapper from "@/shared/DataTableCells/CellWrapper";
import { EvaluatorsRule } from "@/types/automations";
import { LOGS_SOURCE, TRACE_VISIBILITY_MODE } from "@/types/traces";
import { COLUMN_TYPE } from "@/types/shared";
import { Filter } from "@/types/filters";
import TraceLogsSidebarButton from "@/v2/pages-shared/traces/TraceLogsSidebar/TraceLogsSidebarButton";
import { TraceLogsViewConfig } from "@/v2/pages-shared/traces/TraceLogsSidebar/TraceLogsSidebar";

// Evaluation-traces variant of the logs sidebar: a KPI dashboard on top, hidden-visibility
// monitoring traces, isolated column state, and a trimmed default column set (tags / comments /
// feedback-score columns add no signal for LLM-judge monitoring traces, but stay selectable).
const EVALUATION_TRACES_VIEW_CONFIG: TraceLogsViewConfig = {
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

const RuleTracesCell = (context: CellContext<EvaluatorsRule, string>) => {
  const rule = context.row.original;
  const projectId = rule.project_id ?? rule.project_ids?.[0];

  const wrapperProps = {
    metadata: context.column.columnDef.meta,
    tableMetadata: context.table.options.meta,
    className: "items-center justify-end p-0",
  };

  if (!projectId) {
    return <CellWrapper {...wrapperProps} />;
  }

  // Scope the evaluator traces to this rule via metadata.rule_id; logsSource=evaluator adds the
  // source filter and the view config requests hidden-visibility traces.
  const ruleFilters: Filter[] = [
    {
      id: `rule-traces-${rule.id}`,
      field: "metadata",
      key: "rule_id",
      type: COLUMN_TYPE.string,
      operator: "=",
      value: rule.id,
    },
  ];

  return (
    <CellWrapper {...wrapperProps}>
      <TraceLogsSidebarButton
        projectId={projectId}
        logsSource={LOGS_SOURCE.evaluator}
        sourceFilters={ruleFilters}
        title="Evaluation traces"
        label="Go to traces"
        viewConfig={EVALUATION_TRACES_VIEW_CONFIG}
      />
    </CellWrapper>
  );
};

export default RuleTracesCell;
