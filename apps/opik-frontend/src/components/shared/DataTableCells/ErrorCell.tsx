import { CellContext } from "@tanstack/react-table";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { ROW_HEIGHT } from "@/types/shared";
import { BaseTraceDataErrorInfo } from "@/types/traces";

const ErrorCell = <TData,>(
  context: CellContext<TData, BaseTraceDataErrorInfo | undefined>,
) => {
  const value = context.getValue();

  if (!value) return null;

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
      <TooltipWrapper
        content={
          value.message
            ? `Message: ${value.message}`
            : "Error message is not specified"
        }
        stopClickPropagation
      >
        {isSmall ? (
          <span className="truncate">{value.exception_type}</span>
        ) : (
          <div className="size-full overflow-y-auto">
            {value.exception_type}
          </div>
        )}
      </TooltipWrapper>
    </CellWrapper>
  );
};

export default ErrorCell;
