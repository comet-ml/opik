import { CellContext } from "@tanstack/react-table";
import get from "lodash/get";
import isNumber from "lodash/isNumber";
import { ROW_HEIGHT } from "@/types/shared";
import { formatNumericData } from "@/lib/utils";

import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import CellTooltipWrapper from "@/components/shared/DataTableCells/CellTooltipWrapper";

const TextCell = <TData,>(context: CellContext<TData, string>) => {
  const value = context.getValue();

  const rowHeight =
    context.column.columnDef.meta?.overrideRowHeight ??
    context.table.options.meta?.rowHeight ??
    ROW_HEIGHT.small;

  const isSmall = rowHeight === ROW_HEIGHT.small;

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      {isSmall ? (
        <CellTooltipWrapper content={value}>
          <span className="truncate">{value}</span>
        </CellTooltipWrapper>
      ) : (
        <div className="size-full overflow-y-auto whitespace-pre-wrap break-words">
          {value}
        </div>
      )}
    </CellWrapper>
  );
};

type CustomMeta = {
  aggregationKey?: string;
  dataFormatter?: (value: number) => string;
};

const TextAggregationCell = <TData,>(context: CellContext<TData, string>) => {
  const { custom } = context.column.columnDef.meta ?? {};
  const { aggregationKey, dataFormatter = formatNumericData } = (custom ??
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
      <CellTooltipWrapper content={value}>
        <span className="truncate text-light-slate">{value}</span>
      </CellTooltipWrapper>
    </CellWrapper>
  );
};

TextCell.Aggregation = TextAggregationCell;

export default TextCell;
