import { CellContext } from "@tanstack/react-table";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";

const TextCell = <TData,>(context: CellContext<TData, string>) => {
  const value = context.getValue();

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      <span className="truncate">{value}</span>
    </CellWrapper>
  );
};

export default TextCell;
