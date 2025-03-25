import React from "react";
import { ColumnMeta, TableMeta } from "@tanstack/react-table";
import {
  CELL_HORIZONTAL_ALIGNMENT_MAP,
  CELL_VERTICAL_ALIGNMENT_MAP,
} from "@/constants/shared";
import { CELL_VERTICAL_ALIGNMENT, ROW_HEIGHT } from "@/types/shared";
import { cn } from "@/lib/utils";

type CellWrapperProps<TData> = {
  children?: React.ReactNode;
  metadata?: ColumnMeta<TData, unknown>;
  tableMetadata?: TableMeta<TData>;
  className?: string;
  dataCellWrapper?: boolean;
  stopClickPropagation?: boolean;
};

const CellWrapper = <TData,>({
  children,
  metadata,
  tableMetadata,
  className,
  dataCellWrapper = true,
  stopClickPropagation = false,
}: CellWrapperProps<TData>) => {
  const { type } = metadata || {};
  const { rowHeight, rowHeightStyle } = tableMetadata || {};

  const verticalAlignment =
    metadata?.verticalAlignment ??
    (rowHeight === ROW_HEIGHT.small
      ? CELL_VERTICAL_ALIGNMENT.center
      : CELL_VERTICAL_ALIGNMENT.start);

  const verticalAlignClass = CELL_VERTICAL_ALIGNMENT_MAP[verticalAlignment];
  const horizontalAlignClass =
    CELL_HORIZONTAL_ALIGNMENT_MAP[type!] ?? "justify-start";

  return (
    <div
      className={cn(
        "flex size-full py-2 px-3",
        stopClickPropagation && "cursor-auto",
        verticalAlignClass,
        horizontalAlignClass,
        className,
      )}
      style={rowHeightStyle}
      data-cell-wrapper={dataCellWrapper}
      {...(stopClickPropagation && {
        onClick: (event) => event.stopPropagation(),
      })}
    >
      {children}
    </div>
  );
};

export default CellWrapper;
