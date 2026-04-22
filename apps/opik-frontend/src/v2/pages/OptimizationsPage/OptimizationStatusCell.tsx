import React from "react";
import { CellContext } from "@tanstack/react-table";

import CellWrapper from "@/shared/DataTableCells/CellWrapper";
import { OPTIMIZATION_STATUS } from "@/types/optimizations";
import { Tag } from "@/ui/tag";
import { getCellTagSize, TAG_SIZE_MAP } from "@/constants/shared";

import { STATUS_TO_VARIANT_MAP } from "@/constants/experiments";

const OptimizationStatusCell = (context: CellContext<unknown, unknown>) => {
  const status = context.getValue() as OPTIMIZATION_STATUS;
  const variant = STATUS_TO_VARIANT_MAP[status];
  const tagSize = getCellTagSize(context, TAG_SIZE_MAP);

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="gap-1"
    >
      <Tag variant={variant} className="capitalize" size={tagSize}>
        {status}
      </Tag>
    </CellWrapper>
  );
};

export default OptimizationStatusCell;
