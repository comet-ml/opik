import React from "react";
import { CellContext } from "@tanstack/react-table";
import { GitCommitVertical } from "lucide-react";

import CellWrapper from "@/shared/DataTableCells/CellWrapper";
import { Experiment } from "@/types/datasets";

const DatasetVersionCell = <TData extends Experiment>(
  context: CellContext<TData, unknown>,
) => {
  const versionName =
    context.row.original.dataset_version_summary?.version_name;

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      {versionName ? (
        <span className="flex min-w-0 items-center gap-0.5">
          <GitCommitVertical className="size-3 shrink-0 text-muted-slate" />
          <span className="truncate">{versionName}</span>
        </span>
      ) : (
        "-"
      )}
    </CellWrapper>
  );
};

export default DatasetVersionCell;
