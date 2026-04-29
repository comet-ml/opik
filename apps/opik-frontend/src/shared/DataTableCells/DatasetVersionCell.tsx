import React from "react";
import { CellContext } from "@tanstack/react-table";
import { GitCommitVertical } from "lucide-react";
import { Tag } from "@/ui/tag";
import CellWrapper from "@/shared/DataTableCells/CellWrapper";
import { getCellTagSize, TAG_SIZE_MAP } from "@/constants/shared";

const DatasetVersionCell = (context: CellContext<unknown, string>) => {
  const value = context.getValue();

  if (!value) {
    return null;
  }

  const tagSize = getCellTagSize(context, TAG_SIZE_MAP);

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      <Tag
        size={tagSize}
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
