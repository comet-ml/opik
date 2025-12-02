import React from "react";
import { CellContext } from "@tanstack/react-table";
import get from "lodash/get";
import isNumber from "lodash/isNumber";
import useLocalStorageState from "use-local-storage-state";

import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import CellTooltipWrapper from "@/components/shared/DataTableCells/CellTooltipWrapper";
import { AggregatedDuration } from "@/types/shared";
import { formatDuration } from "@/lib/date";

export type PercentileValue = "p50" | "p90" | "p99";

export const DURATION_PERCENTILE_STORAGE_KEY = "experiments-duration-percentile";

type CustomMeta = {
  metricsKey?: string;
  storageKey?: string;
};

const DurationMetricsCell = <TData,>(context: CellContext<TData, unknown>) => {
  const { custom } = context.column.columnDef.meta ?? {};
  const { metricsKey = "duration", storageKey = DURATION_PERCENTILE_STORAGE_KEY } =
    (custom ?? {}) as CustomMeta;

  const [selectedPercentile] = useLocalStorageState<PercentileValue>(storageKey, {
    defaultValue: "p50",
  });

  const value = get(context.row.original, metricsKey) as
    | AggregatedDuration
    | undefined;
  const percentileValue = value?.[selectedPercentile];

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      {isNumber(percentileValue) ? formatDuration(percentileValue) : "-"}
    </CellWrapper>
  );
};

type AggregationCustomMeta = {
  aggregationKey?: string;
  storageKey?: string;
};

const DurationMetricsAggregationCell = <TData,>(
  context: CellContext<TData, string>,
) => {
  const { custom } = context.column.columnDef.meta ?? {};
  const { aggregationKey, storageKey = DURATION_PERCENTILE_STORAGE_KEY } =
    (custom ?? {}) as AggregationCustomMeta;

  const [selectedPercentile] = useLocalStorageState<PercentileValue>(storageKey, {
    defaultValue: "p50",
  });

  const rowId = context.row.id;
  const { aggregationMap } = context.table.options.meta ?? {};

  const data = aggregationMap?.[rowId] ?? {};
  const rawValue = get(
    data,
    `${aggregationKey}.${selectedPercentile}`,
    undefined,
  );
  let displayValue = "-";

  if (isNumber(rawValue)) {
    displayValue = formatDuration(rawValue);
  }

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      <CellTooltipWrapper content={displayValue}>
        <span className="truncate text-light-slate">{displayValue}</span>
      </CellTooltipWrapper>
    </CellWrapper>
  );
};

DurationMetricsCell.Aggregation = DurationMetricsAggregationCell;

export default DurationMetricsCell;
