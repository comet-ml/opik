import React from "react";
import { CellContext } from "@tanstack/react-table";

import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import { CompareConfig } from "@/components/pages/CompareExperimentsPage/ConfigurationTab/ConfigurationTab";
import { ROW_HEIGHT } from "@/types/shared";

const CompareConfigCell: React.FunctionComponent<
  CellContext<CompareConfig, unknown>
> = (context) => {
  const experimentId = context.column?.id;
  const compareConfig = context.row.original;

  const data = compareConfig.data[experimentId];

  if (data === undefined) {
    return null;
  }

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={{
        ...context.table.options.meta,
        rowHeight: ROW_HEIGHT.small,
        rowHeightClass: "min-h-14",
      }}
      className="px-3"
    >
      <div className="max-w-full overflow-hidden whitespace-pre-line break-words">
        {String(data)}
      </div>
    </CellWrapper>
  );
};

export default CompareConfigCell;
