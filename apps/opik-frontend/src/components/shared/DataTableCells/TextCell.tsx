import { CellContext } from "@tanstack/react-table";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";

const TextCell = (context: CellContext<unknown, string>) => {
  const value = context.getValue();

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="px-1 py-2.5"
    >
      <span className="truncate">{value}</span>
    </CellWrapper>
  );
};

export default TextCell;
