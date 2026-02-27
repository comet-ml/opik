import React from "react";
import { CellContext } from "@tanstack/react-table";
import get from "lodash/get";
import isNumber from "lodash/isNumber";

import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";

export const formatPassRate = (
  passRate: number,
  passedCount: number,
  totalCount: number,
): string => {
  return `${(passRate * 100).toFixed(1)}% (${passedCount}/${totalCount})`;
};

const PassRateCell = <TData,>(context: CellContext<TData, unknown>) => {
  const row = context.row.original as Record<string, unknown>;
  const passRate = get(row, "pass_rate") as number | undefined;
  const passedCount = get(row, "passed_count") as number | undefined;
  const totalCount = get(row, "total_count") as number | undefined;

  const display =
    isNumber(passRate) && isNumber(passedCount) && isNumber(totalCount)
      ? formatPassRate(passRate, passedCount, totalCount)
      : undefined;

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      {display ? (
        <TooltipWrapper content={display}>
          <span className="truncate">{display}</span>
        </TooltipWrapper>
      ) : (
        <span className="truncate">-</span>
      )}
    </CellWrapper>
  );
};

type CustomMeta = {
  aggregationKey?: string;
};

const PassRateAggregationCell = <TData,>(
  context: CellContext<TData, unknown>,
) => {
  const { custom } = context.column.columnDef.meta ?? {};
  const { aggregationKey } = (custom ?? {}) as CustomMeta;

  const rowId = context.row.id;
  const { aggregationMap } = context.table.options.meta ?? {};

  const data = aggregationMap?.[rowId] ?? {};
  const passRate = get(data, aggregationKey ?? "", undefined) as
    | number
    | undefined;
  const passedCount = get(data, "passed_count", undefined) as
    | number
    | undefined;
  const totalCount = get(data, "total_count", undefined) as number | undefined;

  const display =
    isNumber(passRate) && isNumber(passedCount) && isNumber(totalCount)
      ? formatPassRate(passRate, passedCount, totalCount)
      : undefined;

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      {display ? (
        <TooltipWrapper content={display}>
          <span className="truncate text-light-slate">{display}</span>
        </TooltipWrapper>
      ) : (
        <span className="truncate text-light-slate">-</span>
      )}
    </CellWrapper>
  );
};

PassRateCell.Aggregation = PassRateAggregationCell;

export default PassRateCell;
