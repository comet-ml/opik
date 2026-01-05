import React from "react";
import { CellContext } from "@tanstack/react-table";
import { GitCommitVertical } from "lucide-react";
import { Tag } from "@/components/ui/tag";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";

const DatasetVersionCell = (context: CellContext<unknown, string>) => {
  const value = context.getValue();

  if (!value) {
    return null;
  }

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      <Tag
        size="md"
        variant="transparent"
        className="flex shrink-0 items-center gap-1"
      >
        <GitCommitVertical className="size-3 text-green-500" />
        {value}
      </Tag>
    </CellWrapper>
  );
};

export default DatasetVersionCell;
