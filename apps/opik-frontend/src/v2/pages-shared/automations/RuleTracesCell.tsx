import React from "react";
import { CellContext } from "@tanstack/react-table";

import CellWrapper from "@/shared/DataTableCells/CellWrapper";
import { EvaluatorsRule } from "@/types/automations";
import { COLUMN_TYPE } from "@/types/shared";
import { Filter } from "@/types/filters";
import TraceLogsSidebarButton from "@/v2/pages-shared/traces/TraceLogsSidebar/TraceLogsSidebarButton";

const RuleTracesCell = (context: CellContext<EvaluatorsRule, string>) => {
  const rule = context.row.original;
  const projectId =
    rule.projects?.[0]?.project_id ?? rule.project_id ?? rule.project_ids?.[0];

  const wrapperProps = {
    metadata: context.column.columnDef.meta,
    tableMetadata: context.table.options.meta,
    className: "items-center justify-end p-0",
  };

  if (!projectId) {
    return <CellWrapper {...wrapperProps} />;
  }

  // Scope the evaluator traces to this rule via metadata.rule_id, as a LOCKED scope: the sidebar
  // always constrains to this rule and the user can't change or remove it via the filter bar, so
  // one evaluator's view can never show another evaluator's traces. This is a trigger only — the
  // sidebar itself is mounted once at the page level (see EvaluationTracesSidebar), so all rows
  // share a single instance instead of mounting one sidebar per row (which raced on the shared
  // tls_* query params and broke pagination/controls).
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
        sourceFilters={ruleFilters}
        lockScope
        scopeLabel={rule.name}
        label="Go to traces"
        renderSidebar={false}
      />
    </CellWrapper>
  );
};

export default RuleTracesCell;
