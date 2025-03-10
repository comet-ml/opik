import React from "react";
import isObject from "lodash/isObject";
import { CellContext } from "@tanstack/react-table";
import { ROW_HEIGHT } from "@/types/shared";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import { prettifyMessage } from "@/lib/traces";

type CustomMeta = {
  fieldType: "input" | "output";
};

const PrettyCell = <TData,>(context: CellContext<TData, string | object>) => {
  const { custom } = context.column.columnDef.meta ?? {};
  const { fieldType = "input" } = (custom ?? {}) as CustomMeta;
  const value = context.getValue() as string | object;
  if (!value) return "";

  const response = prettifyMessage(value, {
    type: fieldType,
  });
  const message = isObject(response.message)
    ? JSON.stringify(value, null, 2)
    : response.message;

  const rowHeight =
    context.column.columnDef.meta?.overrideRowHeight ??
    context.table.options.meta?.rowHeight ??
    ROW_HEIGHT.small;

  let content;

  if (rowHeight === ROW_HEIGHT.small) {
    content = <span className="comet-code truncate">{message}</span>;
  } else {
    content = (
      <div className="comet-code size-full overflow-y-auto whitespace-pre-wrap break-words">
        {message}
      </div>
    );
  }

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
