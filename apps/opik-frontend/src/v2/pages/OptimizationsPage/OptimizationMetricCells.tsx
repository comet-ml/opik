import React from "react";
import { CellContext } from "@tanstack/react-table";
import isObject from "lodash/isObject";

import CellWrapper from "@/shared/DataTableCells/CellWrapper";
import MetricComparisonCell from "@/v2/pages-shared/experiments/MetricComparisonCell/MetricComparisonCell";
import FeedbackScoreTag from "@/shared/FeedbackScoreTag/FeedbackScoreTag";
import { Optimization } from "@/types/optimizations";
import { getFeedbackScore } from "@/lib/feedback-scores";
import {
  formatAsPercentage,
  formatAsDuration,
  formatAsCurrency,
} from "@/lib/optimization-formatters";

export const OptimizationPassRateCell = (
  context: CellContext<unknown, unknown>,
) => {
  const row = context.row.original as Optimization;
  const isEvaluationSuite = (row.experiment_scores?.length ?? 0) > 0;

  if (!isEvaluationSuite) {
    return (
      <CellWrapper
        metadata={context.column.columnDef.meta}
        tableMetadata={context.table.options.meta}
      >
        <span className="comet-body-s text-muted-slate">-</span>
      </CellWrapper>
    );
  }

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      <MetricComparisonCell
        baseline={row.baseline_objective_score}
        current={row.best_objective_score}
        formatter={formatAsPercentage}
        compact
      />
    </CellWrapper>
  );
};

export const OptimizationAccuracyCell = (
  context: CellContext<unknown, unknown>,
) => {
  const row = context.row.original as Optimization;
  const isEvaluationSuite = (row.experiment_scores?.length ?? 0) > 0;

  if (isEvaluationSuite) {
    return (
      <CellWrapper
        metadata={context.column.columnDef.meta}
        tableMetadata={context.table.options.meta}
      >
        <span className="comet-body-s text-muted-slate">-</span>
      </CellWrapper>
    );
  }

  const feedbackScore = getFeedbackScore(
    row.feedback_scores ?? [],
    row.objective_name,
  );

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="gap-1"
    >
      {isObject(feedbackScore) ? (
        <FeedbackScoreTag
          label={feedbackScore.name}
          value={feedbackScore.value}
          className="overflow-hidden"
        />
      ) : (
        "-"
      )}
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
        compact
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
        compact
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
