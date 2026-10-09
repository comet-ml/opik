import React from "react";
import { CellContext } from "@tanstack/react-table";

import CellWrapper from "@/shared/DataTableCells/CellWrapper";
import { extractAssertions } from "@/lib/assertion-converters";
import { DatasetItem } from "@/types/datasets";
import { ExecutionPolicy } from "@/types/test-suites";

export const formatExecutionPolicy = (policy?: ExecutionPolicy) =>
  policy
    ? `${policy.pass_threshold} of ${policy.runs_per_item} runs must pass`
    : "—";

export const SuiteItemAssertionsCell = (
  context: CellContext<DatasetItem, unknown>,
) => {
  const count = extractAssertions(context.row.original.evaluators ?? []).length;
  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="justify-center"
    >
      {count || <span className="text-muted-slate">&mdash;</span>}
    </CellWrapper>
  );
};

export const SuiteItemExecutionPolicyCell = (
  context: CellContext<DatasetItem, unknown>,
) => {
  const policy = context.row.original.execution_policy;
  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      {policy ? (
        formatExecutionPolicy(policy)
      ) : (
        <span className="text-muted-slate">Suite default</span>
      )}
    </CellWrapper>
  );
};
