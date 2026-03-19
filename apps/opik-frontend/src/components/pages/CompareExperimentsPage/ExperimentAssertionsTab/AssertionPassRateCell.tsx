import { CellContext } from "@tanstack/react-table";

import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { Tag } from "@/components/ui/tag";
import { AssertionAggregation } from "@/types/datasets";
import { formatPassRate } from "@/components/shared/DataTableCells/PassRateCell";

const AssertionPassRateCell = <TData,>(
  context: CellContext<TData, unknown>,
) => {
  const agg = context.getValue() as AssertionAggregation | undefined;

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      {agg ? (
        <TooltipWrapper
          content={formatPassRate(
            agg.pass_rate,
            agg.passed_count,
            agg.total_count,
          )}
        >
          <Tag variant={agg.pass_rate === 1 ? "green" : "red"} size="md">
            {Math.round(agg.pass_rate * 100)}%
          </Tag>
        </TooltipWrapper>
      ) : (
        <span className="truncate">-</span>
      )}
    </CellWrapper>
  );
};

export default AssertionPassRateCell;
