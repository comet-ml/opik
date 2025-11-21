import React from "react";
import { CellContext } from "@tanstack/react-table";

import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import ColoredTag from "@/components/shared/ColoredTag/ColoredTag";

const PlaygroundTagsCell: React.FunctionComponent<
  CellContext<never, unknown>
> = (context) => {
  const tags = context.getValue() as string[];

  if (!Array.isArray(tags) || tags.length === 0) {
    return (
      <CellWrapper
        metadata={context.column.columnDef.meta}
        tableMetadata={context.table.options.meta}
        className="pt-5"
      >
        <div className="size-full">
          <div className="h-[var(--cell-top-height)]" />
          <div className="h-[calc(100%-var(--cell-top-height))]" />
        </div>
      </CellWrapper>
    );
  }

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="pt-5"
    >
      <div className="size-full">
        <div className="h-[var(--cell-top-height)]" />
        <div className="h-[calc(100%-var(--cell-top-height))] overflow-auto">
          <div className="flex flex-row flex-wrap gap-1.5">
            {tags.sort().map((tag) => {
              return <ColoredTag label={tag} key={tag} className="shrink-0" />;
            })}
          </div>
        </div>
      </div>
    </CellWrapper>
  );
};

export default PlaygroundTagsCell;
