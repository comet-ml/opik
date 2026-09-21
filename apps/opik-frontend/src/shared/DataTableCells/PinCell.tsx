import React from "react";
import { CellContext } from "@tanstack/react-table";
import { Star } from "lucide-react";

import { Button } from "@/ui/button";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import CellWrapper from "@/shared/DataTableCells/CellWrapper";
import { cn } from "@/lib/utils";

const PinCell = <TData,>(context: CellContext<TData, unknown>) => {
  const isPinned = Boolean(context.row.getIsPinned());
  const label = isPinned ? "Unpin" : "Pin to top";

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="justify-center px-0"
      stopClickPropagation
    >
      <TooltipWrapper content={label}>
        <Button
          variant="minimal"
          size="icon-2xs"
          aria-label={label}
          aria-pressed={isPinned}
          className={cn(
            "shrink-0 rounded",
            isPinned
              ? "text-chart-yellow"
              : "text-light-slate opacity-0 group-hover/row:opacity-100",
          )}
          onClick={() => context.row.pin(isPinned ? false : "top")}
        >
          <Star className={cn(isPinned && "fill-current")} />
        </Button>
      </TooltipWrapper>
    </CellWrapper>
  );
};

export default PinCell;
