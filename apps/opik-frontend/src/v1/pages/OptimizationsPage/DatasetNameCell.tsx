import React from "react";
import { CellContext } from "@tanstack/react-table";
import { Database } from "lucide-react";
import { Link } from "@tanstack/react-router";

import CellWrapper from "@/shared/DataTableCells/CellWrapper";
import { Button } from "@/ui/button";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { Optimization } from "@/types/optimizations";
import useAppStore from "@/store/AppStore";

const DatasetNameCell = (context: CellContext<unknown, unknown>) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const row = context.row.original as Optimization;
  const name = row.dataset_name ?? "-";

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="group relative"
    >
      <TooltipWrapper content={name}>
        <span className="comet-body-s truncate">{name}</span>
      </TooltipWrapper>

      {row.dataset_id && (
        <Link
          to="/$workspaceName/datasets/$datasetId/items"
          params={{ workspaceName, datasetId: row.dataset_id }}
          onClick={(e) => e.stopPropagation()}
        >
          <Button
            className="absolute right-1 top-1/2 -translate-y-1/2 opacity-0 group-hover:opacity-100"
            size="icon-xs"
            variant="outline"
          >
            <Database />
          </Button>
        </Link>
      )}
    </CellWrapper>
  );
};

export default DatasetNameCell;
