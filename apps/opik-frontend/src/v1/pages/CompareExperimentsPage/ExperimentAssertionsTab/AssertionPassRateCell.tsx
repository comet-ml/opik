import { CellContext } from "@tanstack/react-table";

import CellWrapper from "@/shared/DataTableCells/CellWrapper";
import { Tag } from "@/ui/tag";
import { AssertionScoreAverage } from "@/types/datasets";

const AssertionPassRateCell = <TData,>(
  context: CellContext<TData, unknown>,
) => {
  const score = context.getValue() as AssertionScoreAverage | undefined;

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      {score ? (
        <Tag variant={score.value === 1 ? "green" : "red"} size="md">
          {Math.round(score.value * 100)}%
        </Tag>
      ) : (
        <span className="truncate">-</span>
      )}
    </CellWrapper>
  );
};

export default AssertionPassRateCell;
