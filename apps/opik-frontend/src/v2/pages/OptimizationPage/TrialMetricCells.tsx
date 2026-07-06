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
import PercentageTrend, {
  PercentageTrendType,
} from "@/shared/PercentageTrend/PercentageTrend";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";

type TrialCellContext = CellContext<AggregatedCandidate, unknown>;

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

// The trend tag sits before the value, and the pair is flush right (the
// column types right-align via CellWrapper). The compact 20px "sm" tag fits
// the 32px rows — the default 24px one overflows.
const TrialMetricCellContent: React.FunctionComponent<TrialMetricCellProps> = ({
  value,
  formatter,
  percentage,
  trend = "direct",
  suffix,
}) => (
  <>
    <PercentageTrend percentage={percentage} trend={trend} size="sm" />
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
      <span className="comet-body-s">Trial #{row.trialNumber}</span>
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
      <span className="comet-body-s">Step {row.stepIndex}</span>
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

  const percentage = getBaselinePercentage(
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
      className="gap-2"
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

  const percentage = getBaselinePercentage(
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
      className="gap-2"
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

  const percentage = getBaselinePercentage(
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
      className="gap-2"
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
