import { CellContext } from "@tanstack/react-table";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import { formatCost } from "@/lib/money";

const CostCell = <TData,>(context: CellContext<TData, string>) => {
  const value = context.getValue();

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      {formatCost(value, { modifier: "short" })}
    </CellWrapper>
  );
};

export default CostCell;
