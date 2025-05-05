import React from "react";
import { CellContext } from "@tanstack/react-table";

import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import { OPTIMIZATION_STATUS } from "@/types/optimizations";
import { Tag } from "@/components/ui/tag";

import { STATUS_TO_VARIANT_MAP } from "@/constants/experiments";

const OptimizationStatusCell = (context: CellContext<unknown, unknown>) => {
  const status = context.getValue() as OPTIMIZATION_STATUS;
  const variant = STATUS_TO_VARIANT_MAP[status];

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="gap-1"
    >
      <Tag variant={variant} className="capitalize" size="md">
        {status}
      </Tag>
    </CellWrapper>
  );
};

export default OptimizationStatusCell;
