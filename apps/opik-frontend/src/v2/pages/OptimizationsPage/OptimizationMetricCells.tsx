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

/**
 * The run's best objective score, rendered per run type.
 *
 * This replaces the former separate "Pass rate" and "Accuracy" columns. Those
 * were mutually exclusive — each rendered a literal "-" for the run type it did
 * not handle — so a test-suite run always had an empty Accuracy cell and, more
 * visibly, every Studio dataset run always had an empty Pass rate cell.
 *
 * Note the header stays type-neutral ("Best score") rather than using
 * getObjectiveLabel the way the trials table does: a single optimization run has
 * one type, but this list can mix test-suite and dataset runs, so no static
 * header can be per-row correct. Which metric produced the score is already
 * carried by the sibling "Metric" column and, for dataset runs, by the score
 * tag's own label.
 */
export const OptimizationObjectiveScoreCell = (
  context: CellContext<unknown, unknown>,
) => {
  const row = context.row.original as Optimization;
  const isTestSuite = (row.experiment_scores?.length ?? 0) > 0;

  // Test-suite runs report a pass rate, which is meaningful as a
  // baseline-vs-best delta.
  if (isTestSuite) {
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
          size="sm"
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
      <span className="comet-body-xs">
        {row.total_optimization_cost != null
          ? formatAsCurrency(row.total_optimization_cost)
          : "-"}
      </span>
    </CellWrapper>
  );
};
