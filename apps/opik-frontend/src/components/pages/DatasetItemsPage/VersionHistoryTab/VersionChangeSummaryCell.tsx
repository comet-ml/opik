import React from "react";
import { CellContext } from "@tanstack/react-table";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { DatasetVersion } from "@/types/datasets";
import { Tag } from "@/components/ui/tag";

const VersionChangeSummaryCell: React.FC<
  CellContext<DatasetVersion, unknown>
> = (context) => {
  const version = context.row.original;
  const itemsAdded = version.items_added || 0;
  const itemsModified = version.items_modified || 0;
  const itemsDeleted = version.items_deleted || 0;

  const hasChanges = itemsAdded > 0 || itemsModified > 0 || itemsDeleted > 0;

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="gap-2"
    >
      {!hasChanges ? (
        <span className="text-muted-foreground">-</span>
      ) : (
        <div className="flex items-center gap-2">
          {itemsAdded > 0 && (
            <TooltipWrapper content="Items added">
              <Tag variant="green" size="md">
                + {itemsAdded}
              </Tag>
            </TooltipWrapper>
          )}
          {itemsModified > 0 && (
            <TooltipWrapper content="Items modified">
              <Tag variant="blue" size="md">
                ~ {itemsModified}
              </Tag>
            </TooltipWrapper>
          )}
          {itemsDeleted > 0 && (
            <TooltipWrapper content="Items deleted">
              <Tag variant="red" size="md">
                − {itemsDeleted}
              </Tag>
            </TooltipWrapper>
          )}
        </div>
      )}
    </CellWrapper>
  );
};

export default VersionChangeSummaryCell;
