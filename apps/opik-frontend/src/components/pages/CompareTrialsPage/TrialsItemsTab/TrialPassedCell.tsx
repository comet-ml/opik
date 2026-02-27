import React from "react";
import { CellContext } from "@tanstack/react-table";

import { ExperimentItem, ExperimentsCompare } from "@/types/datasets";
import VerticallySplitCellWrapper from "@/components/pages-shared/experiments/VerticallySplitCellWrapper/VerticallySplitCellWrapper";

const TrialPassedCell: React.FC<CellContext<ExperimentsCompare, unknown>> = (
  context,
) => {
  const experimentCompare = context.row.original;

  const renderContent = (item: ExperimentItem | undefined) => {
    if (!item?.feedback_scores?.length) {
      return <span>-</span>;
    }

    const passed = item.feedback_scores.every((s) => s.value >= 1.0);

    return (
      <span className={passed ? "text-success" : "text-destructive"}>
        {passed ? "Yes" : "No"}
      </span>
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

export default TrialPassedCell;
