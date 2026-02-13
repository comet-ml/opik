import { CellContext } from "@tanstack/react-table";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { formatDate, getTimeFromNow } from "@/lib/date";

const SHORT_TIMEZONE = Intl.DateTimeFormat("en-US", {
  timeZoneName: "short",
})
  .formatToParts(new Date())
  .find((p) => p.type === "timeZoneName")?.value;

const TimeCell = <TData,>(context: CellContext<TData, string>) => {
  const value = context.getValue();
  const relativeTime = getTimeFromNow(value);
  const fullDate = formatDate(value);
  const tooltip = SHORT_TIMEZONE ? `${fullDate} ${SHORT_TIMEZONE}` : fullDate;

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      {relativeTime ? (
        <TooltipWrapper content={tooltip}>
          <span className="truncate">{relativeTime}</span>
        </TooltipWrapper>
      ) : (
        "-"
      )}
    </CellWrapper>
  );
};

export default TimeCell;
