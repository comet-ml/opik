import React from "react";
import { CellContext } from "@tanstack/react-table";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import { ExpandingFeedbackScoreRow } from "../types";
import { getIsParentFeedbackScoreRow } from "../utils";
import BaseTraceDataTypeIcon from "@/components/pages-shared/traces/TraceDetailsPanel/BaseTraceDataTypeIcon";
import { SPAN_TYPE } from "@/types/traces";
import { SPAN_TYPE_LABELS_MAP } from "@/constants/traces";

const TypeCell = (context: CellContext<ExpandingFeedbackScoreRow, string>) => {
  const rowData = context.row.original;
  const isParentRow = getIsParentFeedbackScoreRow(rowData);

  // Don't show type for parent rows (aggregated)
  if (isParentRow) {
    return null;
  }

  const spanType = rowData.span_type as SPAN_TYPE | undefined;

  if (!spanType) {
    return null;
  }

  const typeLabel = SPAN_TYPE_LABELS_MAP[spanType] || spanType;

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      <div className="flex w-full min-w-0 items-center gap-1.5">
        <BaseTraceDataTypeIcon type={spanType} />
        <span className="comet-body-s min-w-0 flex-1 truncate">
          {typeLabel}
        </span>
      </div>
    </CellWrapper>
  );
};

export default TypeCell;
