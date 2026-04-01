import { CellContext } from "@tanstack/react-table";

import CellWrapper from "@/shared/DataTableCells/CellWrapper";
import { Tag } from "@/ui/tag";
import { getCellTagSize, TAG_SIZE_MAP } from "@/constants/shared";
import { AssertionScoreAverage } from "@/types/datasets";

const AssertionPassRateCell = <TData,>(
  context: CellContext<TData, unknown>,
) => {
  const score = context.getValue() as AssertionScoreAverage | undefined;
  const tagSize = getCellTagSize(context, TAG_SIZE_MAP);

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      {score ? (
        <Tag variant={score.value === 1 ? "green" : "red"} size={tagSize}>
          {Math.round(score.value * 100)}%
        </Tag>
      ) : (
        <span className="truncate">-</span>
      )}
    </CellWrapper>
  );
};

export default AssertionPassRateCell;
