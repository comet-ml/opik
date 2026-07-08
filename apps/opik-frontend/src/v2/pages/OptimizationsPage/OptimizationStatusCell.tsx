import React from "react";
import { CellContext } from "@tanstack/react-table";

import CellWrapper from "@/shared/DataTableCells/CellWrapper";
import { OPTIMIZATION_STATUS } from "@/types/optimizations";
import { getCellTagSize, TAG_SIZE_MAP } from "@/constants/shared";
import OptimizationStatusTag from "@/v2/pages-shared/optimizations/OptimizationStatusTag";

const OptimizationStatusCell = (context: CellContext<unknown, unknown>) => {
  const status = context.getValue() as OPTIMIZATION_STATUS;
  const tagSize = getCellTagSize(context, TAG_SIZE_MAP);

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="gap-1"
    >
      <OptimizationStatusTag status={status} size={tagSize} />
    </CellWrapper>
  );
};

export default OptimizationStatusCell;
