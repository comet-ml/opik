import React from "react";
import { CellContext } from "@tanstack/react-table";
import isNumber from "lodash/isNumber";

import CellWrapper from "@/shared/DataTableCells/CellWrapper";
import { AggregatedCandidate } from "@/types/optimizations";
import {
  formatAsDuration,
  formatAsCurrency,
  formatAsPercentage,
} from "@/lib/optimization-formatters";
import { calcFormatterAwarePercentage } from "@/lib/percentage";
import { PercentageTrendType } from "@/shared/PercentageTrend/PercentageTrend";
import MetricTrendPill from "@/shared/PercentageTrend/MetricTrendPill";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import {
  isInProgressTrialStatus,
  type TrialStatus,
} from "@/v2/pages-shared/experiments/OptimizationProgressChart/optimizationChartUtils";

type TrialCellContext = CellContext<AggregatedCandidate, unknown>;

/**
 * True while this row's evaluation is still in flight.
 *
 * Every metric on a mid-evaluation trial is computed over the items scored so
 * far, so a delta against the (fully evaluated) baseline compares different
 * denominators — a 5-of-30 partial average read "-75%" against a 30-item
 * baseline in the OPIK-7460 repro. The provisional value itself is still worth
 * showing; the comparison is not, so callers drop the trend pill (OPIK-7460).
 */
const getIsRowInProgress = (
  context: TrialCellContext,
  candidateId: string,
): boolean => {
  const { custom } = context.column.columnDef.meta ?? {};
  const { statusMap } = (custom ?? {}) as {
    statusMap?: Map<string, TrialStatus>;
  };
  const status = statusMap?.get(candidateId);
  return status !== undefined && isInProgressTrialStatus(status);
};

// Plain helper (not memoized): call sites pass fresh inline accessors each
// render, so a useMemo here would never hit its cache — and the calc is a
// single arithmetic op, so caching buys nothing.
const getBaselinePercentage = (
  baseline: AggregatedCandidate | undefined,
  candidateId: string,
  value: number | undefined,
  baselineAccessor: (c: AggregatedCandidate) => number | undefined,
  formatter?: (v: number) => string,
): number | undefined => {
  if (candidateId === baseline?.candidateId) return undefined;
  return calcFormatterAwarePercentage(
    value,
    baseline ? baselineAccessor(baseline) : undefined,
    formatter,
  );
};

type TrialMetricCellProps = {
  value?: number;
  formatter: (v: number) => string;
  percentage?: number;
  trend?: PercentageTrendType;
  suffix?: string;
};

// The trend pill sits before the value, and the pair is flush right (the
// column types right-align via CellWrapper). Uses the shared MetricTrendPill
// so trials render deltas identically to the optimization table.
const TrialMetricCellContent: React.FunctionComponent<TrialMetricCellProps> = ({
  value,
  formatter,
  percentage,
  trend = "direct",
  suffix,
}) => (
  <>
    <MetricTrendPill percentage={percentage} trend={trend} />
    {isNumber(value) ? (
      <TooltipWrapper content={String(value)}>
        <span>
          {formatter(value)}
          {suffix}
        </span>
      </TooltipWrapper>
    ) : (
      "-"
    )}
  </>
);

export const TrialNumberCell = (context: TrialCellContext) => {
  const row = context.row.original;
  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      <span className="min-w-0 truncate">
        {/* The baseline is not a trial and carries no number (OPIK-7589). */}
        {row.trialNumber == null ? "Baseline" : `Trial #${row.trialNumber}`}
      </span>
    </CellWrapper>
  );
};

export const TrialStepCell = (context: TrialCellContext) => {
  const row = context.row.original;
  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      <span className="min-w-0 truncate">Step {row.stepIndex}</span>
    </CellWrapper>
  );
};

export const TrialAccuracyCell = (context: TrialCellContext) => {
  const row = context.row.original;
  const { custom } = context.column.columnDef.meta ?? {};
  const { baselineCandidate, isTestSuite } = (custom ?? {}) as {
    baselineCandidate?: AggregatedCandidate;
    isTestSuite?: boolean;
  };

  const percentage = getIsRowInProgress(context, row.candidateId)
    ? undefined
    : getBaselinePercentage(
        baselineCandidate,
        row.candidateId,
        row.score,
        (b) => b.score,
        formatAsPercentage,
      );

  const passRateFraction =
    isTestSuite && isNumber(row.score) && row.totalCount > 0
      ? ` (${row.passedCount}/${row.totalCount})`
      : "";

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="gap-1.5"
    >
      <TrialMetricCellContent
        value={row.score}
        formatter={formatAsPercentage}
        percentage={percentage}
        suffix={passRateFraction}
      />
    </CellWrapper>
  );
};

export const TrialCandidateCostCell = (context: TrialCellContext) => {
  const row = context.row.original;
  const { custom } = context.column.columnDef.meta ?? {};
  const { baselineCandidate } = (custom ?? {}) as {
    baselineCandidate?: AggregatedCandidate;
  };

  const percentage = getIsRowInProgress(context, row.candidateId)
    ? undefined
    : getBaselinePercentage(
        baselineCandidate,
        row.candidateId,
        row.runtimeCost,
        (b) => b.runtimeCost,
        formatAsCurrency,
      );

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="gap-1.5"
    >
      <TrialMetricCellContent
        value={row.runtimeCost}
        formatter={formatAsCurrency}
        percentage={percentage}
        trend="inverted"
      />
    </CellWrapper>
  );
};

export const TrialCandidateLatencyCell = (context: TrialCellContext) => {
  const row = context.row.original;
  const { custom } = context.column.columnDef.meta ?? {};
  const { baselineCandidate } = (custom ?? {}) as {
    baselineCandidate?: AggregatedCandidate;
  };

  const percentage = getIsRowInProgress(context, row.candidateId)
    ? undefined
    : getBaselinePercentage(
        baselineCandidate,
        row.candidateId,
        row.latencyP50,
        (b) => b.latencyP50,
        formatAsDuration,
      );

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="gap-1.5"
    >
      <TrialMetricCellContent
        value={row.latencyP50}
        formatter={formatAsDuration}
        percentage={percentage}
        trend="inverted"
      />
    </CellWrapper>
  );
};
