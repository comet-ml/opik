import React from "react";
import { CellContext } from "@tanstack/react-table";

import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import MetricComparisonCell from "@/components/pages-shared/experiments/MetricComparisonCell/MetricComparisonCell";
import { Optimization } from "@/types/optimizations";
import {
  formatAsPercentage,
  formatAsDuration,
  formatAsCurrency,
} from "@/lib/optimization-formatters";

export const OptimizationAccuracyCell = (
  context: CellContext<unknown, unknown>,
) => {
  const row = context.row.original as Optimization;
  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      <MetricComparisonCell
        baseline={row.baseline_objective_score}
        current={row.best_objective_score}
        formatter={formatAsPercentage}
      />
    </CellWrapper>
  );
};

export const OptimizationLatencyCell = (
  context: CellContext<unknown, unknown>,
) => {
  const row = context.row.original as Optimization;
  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      <MetricComparisonCell
        baseline={row.baseline_duration}
        current={row.best_duration}
        formatter={formatAsDuration}
        trend="inverted"
      />
    </CellWrapper>
  );
};

export const OptimizationCostCell = (
  context: CellContext<unknown, unknown>,
) => {
  const row = context.row.original as Optimization;
  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      <MetricComparisonCell
        baseline={row.baseline_cost}
        current={row.best_cost}
        formatter={formatAsCurrency}
        trend="inverted"
      />
    </CellWrapper>
  );
};

export const OptimizationTotalCostCell = (
  context: CellContext<unknown, unknown>,
) => {
  const row = context.row.original as Optimization;
  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      <span className="comet-body-s">
        {row.total_optimization_cost != null
          ? formatAsCurrency(row.total_optimization_cost)
          : "-"}
      </span>
    </CellWrapper>
  );
};
