import React from "react";
import { CellContext } from "@tanstack/react-table";

import CellWrapper from "@/shared/DataTableCells/CellWrapper";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { EnvironmentSquare } from "@/shared/EnvironmentLabel/EnvironmentLabel";
import { Environment } from "@/types/environments";

const EnvironmentNameCell: React.FunctionComponent<
  CellContext<Environment, unknown>
> = (context) => {
  const { name, color } = context.row.original;

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="gap-1.5"
    >
      <TooltipWrapper content={name} stopClickPropagation>
        <div className="flex max-w-full items-center gap-1.5 rounded-md border border-transparent px-2">
          <EnvironmentSquare color={color} />
          <div className="comet-body-xs-accented min-w-0 flex-1 truncate text-muted-slate">
            {name}
          </div>
        </div>
      </TooltipWrapper>
    </CellWrapper>
  );
};

export default EnvironmentNameCell;
