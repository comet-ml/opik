import React from "react";
import { CellContext } from "@tanstack/react-table";
import get from "lodash/get";
import isNumber from "lodash/isNumber";

import { formatCost, FormatCostOptions } from "@/lib/money";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
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
      <TooltipWrapper content={formatCost(value, { modifier: "full" })}>
        <span>{formatCost(value)}</span>
      </TooltipWrapper>
    </CellWrapper>
  );
};

type CompareCustomMeta = {
  accessor: string;
  formatter?: (value: number, config?: FormatCostOptions) => string;
};

const CompareCostCell: React.FC<CellContext<ExperimentsCompare, unknown>> = (
  context,
) => {
  const experimentCompare = context.row.original;
  const { custom } = context.column.columnDef.meta ?? {};
  const { accessor, formatter = formatCost } = (custom ??
    {}) as CompareCustomMeta;

  const renderContent: SplitCellRenderContent = (item?: ExperimentItem) => {
    const value = get(item, accessor);
    if (!isNumber(value)) return "-";

    return (
      <TooltipWrapper content={formatCost(value, { modifier: "full" })}>
        <span>{formatter(value)}</span>
      </TooltipWrapper>
    );
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
    value = dataFormatter(rawValue, {});
  }

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      {isNumber(rawValue) ? (
        <TooltipWrapper content={formatCost(rawValue, { modifier: "full" })}>
          <span className="truncate text-light-slate">{value}</span>
        </TooltipWrapper>
      ) : (
        <span className="truncate text-light-slate">{value}</span>
      )}
    </CellWrapper>
  );
};

CostCell.Aggregation = CostAggregationCell;

export default CostCell;
