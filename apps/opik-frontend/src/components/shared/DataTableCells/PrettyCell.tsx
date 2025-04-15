import React, { useMemo } from "react";
import isObject from "lodash/isObject";
import { CellContext } from "@tanstack/react-table";
import { ROW_HEIGHT } from "@/types/shared";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import CellTooltipWrapper from "@/components/shared/DataTableCells/CellTooltipWrapper";
import { prettifyMessage } from "@/lib/traces";

type CustomMeta = {
  fieldType: "input" | "output";
};

const MAX_LENGTH = 2000;

const PrettyCell = <TData,>(context: CellContext<TData, string | object>) => {
  const { custom } = context.column.columnDef.meta ?? {};
  const { fieldType = "input" } = (custom ?? {}) as CustomMeta;
  const value = context.getValue() as string | object;

  const rawValue = useMemo(() => {
    let text = "";
    if (isObject(value)) {
      text = JSON.stringify(value, null, 2);
    } else {
      text = value;
    }

    return text;
  }, [value]);

  const hasExceededLimit = useMemo(
    () => rawValue.length > MAX_LENGTH,
    [rawValue],
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
          content={
            hasExceededLimit
              ? "It has exceeded the preview limit, open trace details to see details"
              : message
          }
        >
          <span className="comet-code truncate">
            {hasExceededLimit ? rawValue.slice(0, MAX_LENGTH) + "..." : message}
          </span>
        </CellTooltipWrapper>
      );
    }
    return (
      <div className="comet-code size-full overflow-y-auto whitespace-pre-wrap break-words">
        {hasExceededLimit
          ? "It has exceeded the preview limit, open trace details to see details"
          : message}
      </div>
    );
  }, [isSmall, message, hasExceededLimit, rawValue]);

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
