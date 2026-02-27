import { CellContext } from "@tanstack/react-table";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import {
  METRIC_TYPE_LABELS,
  BehaviorDisplayRow,
} from "@/types/evaluation-suites";
import { getConfigTooltip } from "./useEvaluatorDisplayRows";

function MetricTypeCell(
  context: CellContext<BehaviorDisplayRow, unknown>,
): React.ReactElement {
  const row = context.row.original;

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      <TooltipWrapper content={getConfigTooltip(row.type, row.config)}>
        <span className="cursor-default">{METRIC_TYPE_LABELS[row.type]}</span>
      </TooltipWrapper>
    </CellWrapper>
  );
}

export default MetricTypeCell;
