import { CellContext } from "@tanstack/react-table";
import { DatasetVersion } from "@/types/datasets";
import CellWrapper from "@/shared/DataTableCells/CellWrapper";
import CellTooltipWrapper from "@/shared/DataTableCells/CellTooltipWrapper";

const VersionNoteCell = (context: CellContext<DatasetVersion, string>) => {
  const value = context.getValue();

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      <CellTooltipWrapper content={value}>
        <span className="truncate">{value}</span>
      </CellTooltipWrapper>
    </CellWrapper>
  );
};

export default VersionNoteCell;
