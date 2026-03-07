import React from "react";
import { CellContext } from "@tanstack/react-table";
import { Rocket } from "lucide-react";

import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import { Button } from "@/components/ui/button";
import { OPTIMIZATION_STATUS, Optimization } from "@/types/optimizations";

const OptimizationDeployCell = (context: CellContext<unknown, unknown>) => {
  const row = context.row.original as Optimization;
  const isCompleted = row.status === OPTIMIZATION_STATUS.COMPLETED;

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      stopClickPropagation
    >
      <Button
        variant="outline"
        size="2xs"
        disabled={!isCompleted}
        onClick={() => {
          window.alert("Deploy as blueprint — coming soon");
        }}
      >
        <Rocket className="mr-1 size-3.5" />
        Deploy
      </Button>
    </CellWrapper>
  );
};

export default OptimizationDeployCell;
