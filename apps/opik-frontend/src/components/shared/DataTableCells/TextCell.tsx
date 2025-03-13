import { CellContext } from "@tanstack/react-table";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import CellTooltipWrapper from "@/components/shared/DataTableCells/CellTooltipWrapper";
import { ROW_HEIGHT } from "@/types/shared";

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
        <div className="size-full overflow-y-auto">{value}</div>
      )}
    </CellWrapper>
  );
};

export default TextCell;
