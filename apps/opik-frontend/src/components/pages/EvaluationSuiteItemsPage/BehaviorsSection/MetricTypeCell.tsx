import { CellContext } from "@tanstack/react-table";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import {
  METRIC_TYPE_LABELS,
  BehaviorDisplayRow,
} from "@/types/evaluation-suites";
import { getConfigTooltip } from "./useBehaviorDisplayRows";

const MetricTypeCell: React.FunctionComponent<
  CellContext<BehaviorDisplayRow, unknown>
> = (context) => {
  const row = context.row.original;

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      <TooltipWrapper
        content={getConfigTooltip(row.metric_type, row.metric_config)}
      >
        <span className="cursor-default">
          {METRIC_TYPE_LABELS[row.metric_type]}
        </span>
      </TooltipWrapper>
    </CellWrapper>
  );
};

export default MetricTypeCell;
