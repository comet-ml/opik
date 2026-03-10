import React from "react";
import { CellContext } from "@tanstack/react-table";
import isNumber from "lodash/isNumber";

import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import { AggregatedCandidate } from "@/types/optimizations";
import {
  formatAsDuration,
  formatAsCurrency,
  formatAsPercentage,
} from "@/lib/optimization-formatters";
import PercentageTrend, {
  PercentageTrendType,
} from "@/components/shared/PercentageTrend/PercentageTrend";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";

const getBaselineCandidate = (candidates?: AggregatedCandidate[]) =>
  candidates?.find((c) => c.stepIndex === 0);

const calcPercentageVsBaseline = (
  value: number | undefined,
  baselineValue: number | undefined,
  candidateId: string,
  baselineCandidateId?: string,
): number | undefined => {
  if (
    isNumber(value) &&
    isNumber(baselineValue) &&
    baselineValue !== 0 &&
    candidateId !== baselineCandidateId
  ) {
    return ((value - baselineValue) / Math.abs(baselineValue)) * 100;
  }
  return undefined;
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
  const { candidates, isEvaluationSuite } = (custom ?? {}) as {
    candidates: AggregatedCandidate[];
    isEvaluationSuite?: boolean;
  };

  const baseline = getBaselineCandidate(candidates);
  const percentage = calcPercentageVsBaseline(
    row.score,
    baseline?.score,
    row.candidateId,
    baseline?.candidateId,
  );

  const passRateFraction =
    isEvaluationSuite && isNumber(row.score) && row.totalDatasetItemCount > 0
      ? ` (${Math.round(row.score * row.totalDatasetItemCount)}/${
          row.totalDatasetItemCount
        })`
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
  const { candidates } = (custom ?? {}) as {
    candidates: AggregatedCandidate[];
  };

  const baseline = getBaselineCandidate(candidates);
  const percentage = calcPercentageVsBaseline(
    row.runtimeCost,
    baseline?.runtimeCost,
    row.candidateId,
    baseline?.candidateId,
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
  const { candidates } = (custom ?? {}) as {
    candidates: AggregatedCandidate[];
  };

  const baseline = getBaselineCandidate(candidates);
  const percentage = calcPercentageVsBaseline(
    row.latencyP50,
    baseline?.latencyP50,
    row.candidateId,
    baseline?.candidateId,
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
