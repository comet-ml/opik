import { CellContext } from "@tanstack/react-table";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import { millisecondsToSeconds } from "@/lib/utils";

const DurationCell = <TData,>(context: CellContext<TData, number>) => {
  const value = context.getValue();

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      {isNaN(value) ? "NA" : `${millisecondsToSeconds(value)}s`}
    </CellWrapper>
  );
};

export default DurationCell;
