import { CellContext } from "@tanstack/react-table";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { formatDate, getTimeFromNow } from "@/lib/date";

const TimeCell = <TData,>(context: CellContext<TData, string>) => {
  const value = context.getValue();
  const relativeTime = getTimeFromNow(value);
  const fullDate = formatDate(value);

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      {relativeTime ? (
        <TooltipWrapper content={fullDate}>
          <span className="truncate">{relativeTime}</span>
        </TooltipWrapper>
      ) : (
        "-"
      )}
    </CellWrapper>
  );
};

export default TimeCell;
