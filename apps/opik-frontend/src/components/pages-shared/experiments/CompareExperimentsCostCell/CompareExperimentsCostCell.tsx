import React from "react";
import { CellContext } from "@tanstack/react-table";

import { ExperimentItem, ExperimentsCompare } from "@/types/datasets";
import VerticallySplitCellWrapper from "@/components/pages-shared/experiments/VerticallySplitCellWrapper/VerticallySplitCellWrapper";
import { formatCost } from "@/lib/money";

const CompareExperimentsCostCell: React.FC<
  CellContext<ExperimentsCompare, unknown>
> = (context) => {
  const experimentCompare = context.row.original;

  const renderContent = (item: ExperimentItem | undefined) => {
    const cost = item?.total_estimated_cost;

    if (!cost) {
      return <span>-</span>;
    }

    return (
      <div className="flex h-4 w-full items-center justify-end">
        <span>{formatCost(cost, { modifier: "short" })}</span>
      </div>
    );
  };

  return (
    <VerticallySplitCellWrapper
      renderContent={renderContent}
      experimentCompare={experimentCompare}
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      rowId={context.row.id}
    />
  );
};

export default CompareExperimentsCostCell;
