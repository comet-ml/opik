import React from "react";
import { CellContext } from "@tanstack/react-table";
import { formatCost } from "@/lib/money";
import CellWrapper from "@/shared/DataTableCells/CellWrapper";
import { Span, Thread, Trace } from "@/types/traces";
import CostBreakdownHoverCard from "./CostBreakdownHoverCard";
import { CostBreakdownTarget } from "./types";

const costCellFor = <TData,>(targetOf: (row: TData) => CostBreakdownTarget) => {
  const EstimatedCostCell = (context: CellContext<TData, string>) => {
    const value = context.getValue();

    return (
      <CellWrapper
        metadata={context.column.columnDef.meta}
        tableMetadata={context.table.options.meta}
      >
        <CostBreakdownHoverCard
          target={targetOf(context.row.original)}
          tooltip={formatCost(value, { modifier: "full" })}
        >
          <span>{formatCost(value)}</span>
        </CostBreakdownHoverCard>
      </CellWrapper>
    );
  };
  return EstimatedCostCell;
};

export const SpanCostCell = costCellFor<Span>((span) => ({
  kind: "span",
  span,
}));

export const TraceCostCell = costCellFor<Trace>((trace) => ({
  kind: "trace",
  projectId: trace.project_id,
  traceId: trace.id,
  cost: trace.total_estimated_cost,
}));

export const ThreadCostCell = costCellFor<Thread>((thread) => ({
  kind: "thread",
  projectId: thread.project_id,
  threadId: thread.id,
  cost: thread.total_estimated_cost,
}));
