import React from "react";
import { CellContext } from "@tanstack/react-table";
import { DatasetItem } from "@/types/datasets";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import CellTooltipWrapper from "@/components/shared/DataTableCells/CellTooltipWrapper";

export const DataObjectCell: React.FC<CellContext<DatasetItem, unknown>> = (
  context,
) => {
  const data = context.row.original.data;
  const text = data ? JSON.stringify(data) : "";

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      <CellTooltipWrapper content={text}>
        <span className="truncate">{text}</span>
      </CellTooltipWrapper>
    </CellWrapper>
  );
};

export default DataObjectCell;
