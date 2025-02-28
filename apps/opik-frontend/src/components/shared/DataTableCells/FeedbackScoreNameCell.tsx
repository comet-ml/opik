import { generateTagVariant } from "@/lib/traces";
import { CellContext } from "@tanstack/react-table";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";

import React from "react";
import { TAG_VARIANTS_COLOR_MAP } from "@/components/ui/tag";

const FeedbackScoreNameCell = (context: CellContext<unknown, string>) => {
  const value = context.getValue();

  const color = TAG_VARIANTS_COLOR_MAP[generateTagVariant(value)!];

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="gap-1.5"
    >
      <div
        className="rounded-[0.15rem] bg-[var(--bg-color)] p-1"
        style={{ "--bg-color": color } as React.CSSProperties}
      />
      <p className="comet-body-s-accented truncate text-muted-slate">{value}</p>
    </CellWrapper>
  );
};

export default FeedbackScoreNameCell;
