import { CellContext } from "@tanstack/react-table";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import { BehaviorDisplayRow } from "@/types/evaluation-suites";
import { getRowNameClassName } from "./useBehaviorDisplayRows";

const BehaviorNameCell: React.FunctionComponent<
  CellContext<BehaviorDisplayRow, unknown>
> = (context) => {
  const row = context.row.original;

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      <span className={getRowNameClassName(row)}>{row.name}</span>
    </CellWrapper>
  );
};

export default BehaviorNameCell;
