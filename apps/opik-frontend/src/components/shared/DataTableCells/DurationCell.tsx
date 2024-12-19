import { CellContext } from "@tanstack/react-table";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import { formatDuration } from "@/lib/date";

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

export default DurationCell;
