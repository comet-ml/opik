import React, { useMemo } from "react";
import isObject from "lodash/isObject";
import { CellContext } from "@tanstack/react-table";
import { ROW_HEIGHT } from "@/types/shared";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import CellTooltipWrapper from "@/components/shared/DataTableCells/CellTooltipWrapper";
import { prettifyMessage } from "@/lib/traces";
import useLocalStorageState from "use-local-storage-state";
import { CompactPrettyView } from "@/components/shared/PrettyView";
import { isTraceOrSpan } from "@/lib/type-guards";

type CustomMeta = {
  fieldType: "input" | "output";
};

const MAX_DATA_LENGTH_KEY = "pretty-cell-data-length-limit";
const MAX_DATA_LENGTH = 10000;
const MAX_DATA_LENGTH_MESSAGE = "Preview limit exceeded";

const PrettyCell = <TData,>(context: CellContext<TData, string | object>) => {
  const [maxDataLength] = useLocalStorageState(MAX_DATA_LENGTH_KEY, {
    defaultValue: MAX_DATA_LENGTH,
  });
  const { custom } = context.column.columnDef.meta ?? {};
  const { fieldType = "input" } = (custom ?? {}) as CustomMeta;
  const value = context.getValue() as string | object | undefined | null;

  // Try to get the full trace/span data from the row
  const rowData = context.row.original;
  const isValidTraceOrSpan = isTraceOrSpan(rowData);

  const rowHeight =
    context.column.columnDef.meta?.overrideRowHeight ??
    context.table.options.meta?.rowHeight ??
    ROW_HEIGHT.small;

  const isSmall = rowHeight === ROW_HEIGHT.small;

  const content = useMemo(() => {
    // If we have valid trace/span data, use the new pretty view
    if (isValidTraceOrSpan) {
      return (
        <CompactPrettyView
          data={rowData}
          type={fieldType}
          maxLength={maxDataLength}
        />
      );
    }

    // Fallback to original logic for other data types
    const rawValue = isObject(value)
      ? JSON.stringify(value, null, 2)
      : value ?? "-";
    const hasExceededLimit = rawValue.length > maxDataLength;

    const response = prettifyMessage(value ?? undefined, { type: fieldType });
    const message = isObject(response.message)
      ? JSON.stringify(value, null, 2)
      : response.message || "";

    if (isSmall) {
      return (
        <CellTooltipWrapper
          content={hasExceededLimit ? MAX_DATA_LENGTH_MESSAGE : message}
        >
          <span className="comet-code truncate">
            {hasExceededLimit
              ? rawValue.slice(0, maxDataLength) + "..."
              : message}
          </span>
        </CellTooltipWrapper>
      );
    }
    return (
      <div className="comet-code size-full overflow-y-auto whitespace-pre-wrap break-words">
        {hasExceededLimit ? MAX_DATA_LENGTH_MESSAGE : message}
      </div>
    );
  }, [isValidTraceOrSpan, rowData, fieldType, maxDataLength, value, isSmall]);

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      {content}
    </CellWrapper>
  );
};

export default PrettyCell;
