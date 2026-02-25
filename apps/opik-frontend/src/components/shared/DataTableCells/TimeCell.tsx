import { CellContext } from "@tanstack/react-table";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { formatDate, getTimeFromNow } from "@/lib/date";

interface TimeCellCustomMeta {
  timeMode?: "relative" | "absolute";
}

const TimeCell = <TData,>(context: CellContext<TData, string>) => {
  const value = context.getValue();
  const customMeta = context.column.columnDef.meta?.custom as
    | TimeCellCustomMeta
    | undefined;
  const timeMode = customMeta?.timeMode ?? "relative";

  const displayValue =
    timeMode === "absolute" ? formatDate(value) : getTimeFromNow(value);
  const tooltip = `${formatDate(value, {
    utc: true,
    includeSeconds: true,
  })} UTC`;

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      {displayValue ? (
        <TooltipWrapper content={tooltip}>
          <span className="truncate">{displayValue}</span>
        </TooltipWrapper>
      ) : (
        "-"
      )}
    </CellWrapper>
  );
};

export default TimeCell;
