import React from "react";
import { CellContext } from "@tanstack/react-table";
import { Database } from "lucide-react";
import { Link } from "@tanstack/react-router";

import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import { Button } from "@/components/ui/button";
import { Optimization } from "@/types/optimizations";
import useAppStore from "@/store/AppStore";

const DatasetNameCell = (context: CellContext<unknown, unknown>) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const row = context.row.original as Optimization;

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="group relative"
    >
      <span className="comet-body-s truncate">{row.dataset_name ?? "-"}</span>

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
