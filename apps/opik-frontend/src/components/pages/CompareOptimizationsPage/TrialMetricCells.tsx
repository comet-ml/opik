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
import PercentageTrend from "@/components/shared/PercentageTrend/PercentageTrend";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";

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

  const baselineCandidate = candidates?.find((c) => c.stepIndex === 0);
  const baselineScore = baselineCandidate?.score;

  let percentage: number | undefined;
  if (
    isNumber(row.score) &&
    isNumber(baselineScore) &&
    baselineScore !== 0 &&
    row.candidateId !== baselineCandidate?.candidateId
  ) {
    percentage = ((row.score - baselineScore) / Math.abs(baselineScore)) * 100;
  }

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
      {isNumber(row.score) ? (
        <TooltipWrapper content={String(row.score)}>
          <span>
            {formatAsPercentage(row.score)}
            {passRateFraction}
          </span>
        </TooltipWrapper>
      ) : (
        "-"
      )}
      <PercentageTrend percentage={percentage} />
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

  const baselineCandidate = candidates?.find((c) => c.stepIndex === 0);
  const baselineCost = baselineCandidate?.runtimeCost;

  let percentage: number | undefined;
  if (
    isNumber(row.runtimeCost) &&
    isNumber(baselineCost) &&
    baselineCost !== 0 &&
    row.candidateId !== baselineCandidate?.candidateId
  ) {
    percentage =
      ((row.runtimeCost - baselineCost) / Math.abs(baselineCost)) * 100;
  }

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="gap-2"
    >
      {isNumber(row.runtimeCost) ? (
        <TooltipWrapper content={String(row.runtimeCost)}>
          <span>{formatAsCurrency(row.runtimeCost)}</span>
        </TooltipWrapper>
      ) : (
        "-"
      )}
      <PercentageTrend percentage={percentage} trend="inverted" />
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

  const baselineCandidate = candidates?.find((c) => c.stepIndex === 0);
  const baselineLatency = baselineCandidate?.latencyP50;

  let percentage: number | undefined;
  if (
    isNumber(row.latencyP50) &&
    isNumber(baselineLatency) &&
    baselineLatency !== 0 &&
    row.candidateId !== baselineCandidate?.candidateId
  ) {
    percentage =
      ((row.latencyP50 - baselineLatency) / Math.abs(baselineLatency)) * 100;
  }

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="gap-2"
    >
      {isNumber(row.latencyP50) ? (
        <TooltipWrapper content={String(row.latencyP50)}>
          <span>{formatAsDuration(row.latencyP50)}</span>
        </TooltipWrapper>
      ) : (
        "-"
      )}
      <PercentageTrend percentage={percentage} trend="inverted" />
    </CellWrapper>
  );
};
