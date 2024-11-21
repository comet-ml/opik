import { generateTagVariant } from "@/lib/traces";
import { CellContext } from "@tanstack/react-table";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";

import React from "react";
import { TAG_VARIANTS_COLOR_MAP } from "@/components/ui/tag";

const FeedbackScoreCell = (context: CellContext<unknown, string>) => {
  const value = context.getValue();

  const color = TAG_VARIANTS_COLOR_MAP[generateTagVariant(value)!];
  // ALEX
  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="gap-1.5"
    >
      <div
        className="p-1 rounded-[0.15rem]"
        style={{ backgroundColor: color }}
      />
      <p className="comet-body-s-accented truncate text-light-slate">{value}</p>
    </CellWrapper>
  );
};

export default FeedbackScoreCell;
