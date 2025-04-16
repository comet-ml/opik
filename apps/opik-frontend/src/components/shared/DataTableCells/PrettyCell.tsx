import React, { useMemo } from "react";
import isObject from "lodash/isObject";
import { CellContext } from "@tanstack/react-table";
import { ROW_HEIGHT } from "@/types/shared";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import CellTooltipWrapper from "@/components/shared/DataTableCells/CellTooltipWrapper";
import { prettifyMessage } from "@/lib/traces";
import useLocalStorageState from "use-local-storage-state";

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

  const rawValue = useMemo(() => {
    let text = "";
    if (isObject(value)) {
      text = JSON.stringify(value, null, 2);
    } else {
      text = value ?? "-";
    }

    return text;
  }, [value]);

  const hasExceededLimit = useMemo(
    () => rawValue.length > maxDataLength,
    [rawValue, maxDataLength],
  );

  const response = useMemo(() => {
    if (!value || hasExceededLimit) {
      return {
        message: "",
        prettified: false,
      };
    }

    return prettifyMessage(value, {
      type: fieldType,
    });
  }, [value, fieldType, hasExceededLimit]);

  const message = useMemo(() => {
    if (isObject(response.message)) {
      return JSON.stringify(value, null, 2);
    }
    return response.message || "";
  }, [response.message, value]);

  const rowHeight =
    context.column.columnDef.meta?.overrideRowHeight ??
    context.table.options.meta?.rowHeight ??
    ROW_HEIGHT.small;

  const isSmall = rowHeight === ROW_HEIGHT.small;

  const content = useMemo(() => {
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
  }, [isSmall, message, hasExceededLimit, rawValue, maxDataLength]);

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
