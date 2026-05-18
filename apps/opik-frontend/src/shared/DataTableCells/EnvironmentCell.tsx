import React from "react";
import { CellContext } from "@tanstack/react-table";

import CellWrapper from "@/shared/DataTableCells/CellWrapper";
import EnvironmentLabel from "@/shared/EnvironmentLabel/EnvironmentLabel";
import { BaseTraceData } from "@/types/traces";

const EnvironmentCell = <TData extends BaseTraceData>(
  context: CellContext<TData, unknown>,
) => {
  const environment = context.row.original.environment;

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      <EnvironmentLabel name={environment} />
    </CellWrapper>
  );
};

export default EnvironmentCell;
