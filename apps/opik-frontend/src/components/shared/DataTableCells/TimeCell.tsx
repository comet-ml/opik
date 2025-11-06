import React from "react";
import { CellContext } from "@tanstack/react-table";
import dayjs from "dayjs";

import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";

type CustomMeta = {
  rawValueKey?: string;
};

const TimeCell = <TData,>(context: CellContext<TData, string>) => {
  const formattedValue = context.getValue();
  const { custom } = context.column.columnDef.meta ?? {};
  const { rawValueKey } = (custom ?? {}) as CustomMeta;

  // Get the raw timestamp for tooltip
  const rawValue = rawValueKey
    ? (context.row.original as Record<string, unknown>)[rawValueKey]
    : undefined;

  const tooltipContent =
    rawValue && typeof rawValue === "string" && dayjs(rawValue).isValid()
      ? dayjs(rawValue).format("MMMM D, YYYY [at] h:mm:ss A")
      : formattedValue;

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      {formattedValue ? (
        <TooltipWrapper content={tooltipContent} stopClickPropagation>
          <span className="truncate">{formattedValue}</span>
        </TooltipWrapper>
      ) : (
        "-"
      )}
    </CellWrapper>
  );
};

export default TimeCell;

