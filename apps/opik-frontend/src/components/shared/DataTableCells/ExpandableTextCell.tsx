import React from "react";
import { CellContext, TableMeta } from "@tanstack/react-table";
import { OnChangeFn } from "@/types/shared";

import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import CellTooltipWrapper from "@/components/shared/DataTableCells/CellTooltipWrapper";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";

type CustomMeta = {
  expandedState: Record<string, boolean>;
  setExpandedState: OnChangeFn<Record<string, boolean>>;
  getShortValue?: (value: string) => string;
  getIsExpandable?: (value: string) => boolean;
};

const ExpandableTextCell = <TData,>(context: CellContext<TData, string>) => {
  const value = context.getValue();
  const { custom } = context.column.columnDef.meta ?? {};
  const { expandedState, setExpandedState, getShortValue, getIsExpandable } =
    (custom ?? {}) as CustomMeta;

  const cellKey = context.row.id;
  const isExpanded = expandedState[cellKey] ?? false;
  const setIsExpanded = (newExpanded: boolean) => {
    setExpandedState((prev) => ({ ...prev, [cellKey]: newExpanded }));
  };

  const shortValue = getShortValue?.(value) ?? value;
  const isExpandable = getIsExpandable?.(value) ?? false;

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={
        {
          ...context.table.options.meta,
          rowHeightStyle: isExpanded
            ? undefined
            : context.table.options.meta?.rowHeightStyle,
        } as TableMeta<TData>
      }
      className="justify-between gap-2"
    >
      <CellTooltipWrapper content={value}>
        <span
          className={cn(
            isExpanded ? "whitespace-pre-wrap break-all" : "truncate",
          )}
        >
          {isExpanded ? value : shortValue}
        </span>
      </CellTooltipWrapper>
      {isExpandable && (
        <Button
          size="2xs"
          variant="ghost"
          onClick={(e) => {
            e.stopPropagation();
            setIsExpanded(!isExpanded);
          }}
          className="shrink-0"
        >
          {isExpanded ? "Collapse" : "Expand"}
        </Button>
      )}
    </CellWrapper>
  );
};

export default ExpandableTextCell;
