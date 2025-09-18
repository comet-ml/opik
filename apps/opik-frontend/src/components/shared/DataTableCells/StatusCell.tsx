import React from "react";
import { CellContext } from "@tanstack/react-table";

import { Tag } from "@/components/ui/tag";
import CustomSquare from "@/icons/custom-square.svg?react";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";

const StatusCell = (context: CellContext<unknown, unknown>) => {
  const { column, table } = context;
  const value = context.getValue() as boolean;

  return (
    <CellWrapper
      metadata={column.columnDef.meta}
      tableMetadata={table.options.meta}
      className="gap-1"
    >
      <Tag variant={value ? "green" : "gray"} size="md">
        <div className="flex items-center gap-1">
          <CustomSquare className="size-3 shrink-0" />
          <span className="truncate">{value ? "Enabled" : "Disabled"}</span>
        </div>
      </Tag>
    </CellWrapper>
  );
};

export default StatusCell;
