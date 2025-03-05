import React from "react";
import { CellContext } from "@tanstack/react-table";
import { ROW_HEIGHT } from "@/types/shared";
import { toString } from "@/lib/utils";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import MarkdownPreview from "@/components/shared/MarkdownPreview/MarkdownPreview";

const MarkdownCell = <TData,>(context: CellContext<TData, string>) => {
  const value = context.getValue() as string;
  if (!value) return "";

  const rowHeight =
    context.column.columnDef.meta?.overrideRowHeight ??
    context.table.options.meta?.rowHeight ??
    ROW_HEIGHT.small;

  let content;

  if (rowHeight === ROW_HEIGHT.small) {
    content = <span className="truncate">{value}</span>;
  } else {
    content = (
      <div className="size-full overflow-y-auto">
        <MarkdownPreview>{toString(value)}</MarkdownPreview>
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

export default MarkdownCell;
