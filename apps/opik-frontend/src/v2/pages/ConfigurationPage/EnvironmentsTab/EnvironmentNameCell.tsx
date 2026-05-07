import React from "react";
import { CellContext } from "@tanstack/react-table";

import CellWrapper from "@/shared/DataTableCells/CellWrapper";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { Environment } from "@/types/environments";
import { DEFAULT_HEX_COLOR, HEX_COLOR_REGEX } from "@/constants/colorVariants";

const EnvironmentNameCell: React.FunctionComponent<
  CellContext<Environment, unknown>
> = (context) => {
  const { name, color } = context.row.original;
  const resolvedColor = HEX_COLOR_REGEX.test(color) ? color : DEFAULT_HEX_COLOR;

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="gap-1.5"
    >
      <div className="flex max-w-full items-center gap-1.5 rounded-md border border-transparent px-2">
        <div
          className="shrink-0 rounded-[0.15rem] p-1"
          style={{ backgroundColor: resolvedColor }}
        />
        <TooltipWrapper content={name} stopClickPropagation>
          <div className="comet-body-xs-accented min-w-0 flex-1 truncate text-muted-slate">
            {name}
          </div>
        </TooltipWrapper>
      </div>
    </CellWrapper>
  );
};

export default EnvironmentNameCell;
