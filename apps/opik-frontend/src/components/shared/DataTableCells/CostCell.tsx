import React from "react";
import { CellContext } from "@tanstack/react-table";
import get from "lodash/get";
import isNumber from "lodash/isNumber";

import { formatCost } from "@/lib/money";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import { ExperimentItem, ExperimentsCompare } from "@/types/datasets";
import VerticallySplitCellWrapper, {
  SplitCellRenderContent,
} from "@/components/pages-shared/experiments/VerticallySplitCellWrapper/VerticallySplitCellWrapper";

const CostCell = <TData,>(context: CellContext<TData, string>) => {
  const value = context.getValue();

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      {formatCost(value, { modifier: "short" })}
    </CellWrapper>
  );
};

const CompareCostCell: React.FC<CellContext<ExperimentsCompare, unknown>> = (
  context,
) => {
  const experimentCompare = context.row.original;

  const renderContent: SplitCellRenderContent = (
    item: ExperimentItem | undefined,
  ) => {
    if (!item?.total_estimated_cost) return "-";
    return formatCost(item.total_estimated_cost, { modifier: "short" });
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

CostCell.Compare = CompareCostCell;

type CustomMeta = {
  aggregationKey?: string;
  dataFormatter?: (value: number, config: unknown) => string;
};

const CostAggregationCell = <TData,>(context: CellContext<TData, string>) => {
  const { custom } = context.column.columnDef.meta ?? {};
  const { aggregationKey, dataFormatter = formatCost } = (custom ??
    {}) as CustomMeta;

  const rowId = context.row.id;
  const { aggregationMap } = context.table.options.meta ?? {};

  const data = aggregationMap?.[rowId] ?? {};
  const rawValue = get(data, aggregationKey ?? "", undefined);
  let value = "-";

  if (isNumber(rawValue)) {
    value = dataFormatter(rawValue, { modifier: "short" });
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

CostCell.Aggregation = CostAggregationCell;

export default CostCell;
