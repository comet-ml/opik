import React from "react";
import { CellContext } from "@tanstack/react-table";

import CellWrapper from "@/shared/DataTableCells/CellWrapper";
import CellTooltipWrapper from "@/shared/DataTableCells/CellTooltipWrapper";
import { Tag } from "@/ui/tag";
import { PreviewCell, PreviewRow } from "./useMappingPreview";

const MappingPreviewCell: React.FunctionComponent<
  CellContext<PreviewRow, unknown>
> = (context) => {
  const cell = context.getValue() as PreviewCell | undefined;

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      {cell?.kind === "value" ? (
        <CellTooltipWrapper content={cell.text}>
          <span className="truncate">{cell.text}</span>
        </CellTooltipWrapper>
      ) : (
        <Tag variant="gray" className="text-foreground">
          {cell?.kind === "deferred" ? "Added on import" : "Empty"}
        </Tag>
      )}
    </CellWrapper>
  );
};

export default MappingPreviewCell;
