import React, { useMemo } from "react";
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
const useBaselinePercentage = (
  baseline: AggregatedCandidate | undefined,
  candidateId: string,
  value: number | undefined,
  baselineAccessor: (c: AggregatedCandidate) => number | undefined,
  formatter?: (v: number) => string,
): number | undefined => {
  return useMemo(() => {
    if (candidateId === baseline?.candidateId) return undefined;
    return calcFormatterAwarePercentage(
      value,
      baseline ? baselineAccessor(baseline) : undefined,
      formatter,
    );
  }, [baseline, candidateId, value, baselineAccessor, formatter]);
};

type TrialMetricCellProps = {
  value?: number;
  formatter: (v: number) => string;
  percentage?: number;
  trend?: PercentageTrendType;
  suffix?: string;
};

const TrialMetricCellContent: React.FunctionComponent<TrialMetricCellProps> = ({
  value,
  formatter,
  percentage,
  trend = "direct",
  suffix,
}) => (
  <>
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
    <PercentageTrend percentage={percentage} trend={trend} />
  </>
);

export const TrialNumberCell = (context: CellContext<unknown, unknown>) => {
  const row = context.row.original as AggregatedCandidate;
  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      <span className="comet-body-s">#{row.trialNumber}</span>
    </CellWrapper>
  );
};

export const TrialStepCell = (context: CellContext<unknown, unknown>) => {
  const row = context.row.original as AggregatedCandidate;
  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      <span className="comet-body-s">Step {row.stepIndex}</span>
    </CellWrapper>
  );
};

export const TrialAccuracyCell = (context: CellContext<unknown, unknown>) => {
  const row = context.row.original as AggregatedCandidate;
  const { custom } = context.column.columnDef.meta ?? {};
  const { baselineCandidate, isEvaluationSuite } = (custom ?? {}) as {
    baselineCandidate?: AggregatedCandidate;
    isEvaluationSuite?: boolean;
  };

  const percentage = useBaselinePercentage(
    baselineCandidate,
    row.candidateId,
    row.score,
    (b) => b.score,
    formatAsPercentage,
  );

  const passRateFraction =
    isEvaluationSuite && isNumber(row.score) && row.totalCount > 0
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

export const TrialCandidateCostCell = (
  context: CellContext<unknown, unknown>,
) => {
  const row = context.row.original as AggregatedCandidate;
  const { custom } = context.column.columnDef.meta ?? {};
  const { baselineCandidate } = (custom ?? {}) as {
    baselineCandidate?: AggregatedCandidate;
  };

  const percentage = useBaselinePercentage(
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

export const TrialCandidateLatencyCell = (
  context: CellContext<unknown, unknown>,
) => {
  const row = context.row.original as AggregatedCandidate;
  const { custom } = context.column.columnDef.meta ?? {};
  const { baselineCandidate } = (custom ?? {}) as {
    baselineCandidate?: AggregatedCandidate;
  };

  const percentage = useBaselinePercentage(
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
