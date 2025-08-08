import React from "react";
import { CellContext } from "@tanstack/react-table";
import get from "lodash/get";
import isNumber from "lodash/isNumber";

import { ExperimentItem, ExperimentsCompare } from "@/types/datasets";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import { formatDuration } from "@/lib/date";
import VerticallySplitCellWrapper, {
  SplitCellRenderContent,
} from "@/components/pages-shared/experiments/VerticallySplitCellWrapper/VerticallySplitCellWrapper";

const DurationCell = <TData,>(context: CellContext<TData, number>) => {
  const value = context.getValue();

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      {formatDuration(value)}
    </CellWrapper>
  );
};

const CompareDurationCell: React.FC<
  CellContext<ExperimentsCompare, unknown>
> = (context) => {
  const experimentCompare = context.row.original;

  const renderContent: SplitCellRenderContent = (
    item: ExperimentItem | undefined,
  ) => {
    return formatDuration(item?.duration);
  };

  return (
    <VerticallySplitCellWrapper
      renderContent={renderContent}
      experimentCompare={experimentCompare}
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      rowId={context.row.id}
    />
  );
};

DurationCell.Compare = CompareDurationCell;

type CustomMeta = {
  aggregationKey?: string;
  dataFormatter?: (value: number) => string;
};

const DurationTextAggregationCell = <TData,>(
  context: CellContext<TData, string>,
) => {
  const { custom } = context.column.columnDef.meta ?? {};
  const { aggregationKey, dataFormatter = formatDuration } = (custom ??
    {}) as CustomMeta;

  const rowId = context.row.id;
  const { aggregationMap } = context.table.options.meta ?? {};

  const data = aggregationMap?.[rowId] ?? {};
  const rawValue = get(data, aggregationKey ?? "", undefined);
  let value = "-";

  if (isNumber(rawValue)) {
    value = dataFormatter(rawValue);
  }

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      <span className="truncate text-light-slate">{value}</span>
    </CellWrapper>
  );
};

DurationCell.Aggregation = DurationTextAggregationCell;

export default DurationCell;
