import { CellContext } from "@tanstack/react-table";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import { SPAN_TYPE } from "@/types/traces";
import { SPAN_TYPE_LABELS_MAP } from "@/constants/traces";
import BaseTraceDataTypeIcon from "@/components/pages-shared/traces/TraceDetailsPanel/BaseTraceDataTypeIcon";

const SpanTypeCell = (context: CellContext<unknown, string>) => {
  const value = context.getValue() as SPAN_TYPE;

  if (!value)
    return (
      <CellWrapper
        metadata={context.column.columnDef.meta}
        tableMetadata={context.table.options.meta}
      >
        -
      </CellWrapper>
    );

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      <div className="flex w-full items-center gap-2 overflow-hidden">
        <BaseTraceDataTypeIcon type={value} />
        <span className="truncate">{SPAN_TYPE_LABELS_MAP[value]}</span>
      </div>
    </CellWrapper>
  );
};

export default SpanTypeCell;
