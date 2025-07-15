import React from "react";
import { CellContext } from "@tanstack/react-table";

import { ExperimentItem, ExperimentsCompare } from "@/types/datasets";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import { formatDuration } from "@/lib/date";
import VerticallySplitCellWrapper, {
  SplitCellRenderContent,
} from "@/components/pages-shared/experiments/VerticallySplitCellWrapper/VerticallySplitCellWrapper";

const DurationCell = <TData,>(context: CellContext<TData, number>) => {
  const value = context.getValue();

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      {formatDuration(value)}
    </CellWrapper>
  );
};

const CompareDurationCell: React.FC<
  CellContext<ExperimentsCompare, unknown>
> = (context) => {
  const experimentCompare = context.row.original;

  const renderContent: SplitCellRenderContent = (
    item: ExperimentItem | undefined,
  ) => {
    return formatDuration(item?.duration);
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

DurationCell.Compare = CompareDurationCell;

export default DurationCell;
