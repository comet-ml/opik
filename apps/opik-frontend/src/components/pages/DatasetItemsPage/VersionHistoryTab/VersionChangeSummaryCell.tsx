import React from "react";
import { CellContext } from "@tanstack/react-table";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import { DatasetVersion } from "@/types/datasets";
import { Tag } from "@/components/ui/tag";

const VersionChangeSummaryCell: React.FC<
  CellContext<DatasetVersion, unknown>
> = (context) => {
  const version = context.row.original;
  const itemsAdded = version.items_added || 0;
  const itemsDeleted = version.items_deleted || 0;

  const hasChanges = itemsAdded > 0 || itemsDeleted > 0;

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
            <Tag variant="green" size="sm">
              + {itemsAdded}
            </Tag>
          )}
          {itemsDeleted > 0 && (
            <Tag variant="red" size="sm">
              − {itemsDeleted}
            </Tag>
          )}
        </div>
      )}
    </CellWrapper>
  );
};

export default VersionChangeSummaryCell;
