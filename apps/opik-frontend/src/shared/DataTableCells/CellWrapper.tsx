import React from "react";
import { ColumnMeta, TableMeta } from "@tanstack/react-table";
import {
  CELL_HORIZONTAL_ALIGNMENT_CLASS_MAP,
  CELL_TEXT_CLASS_MAP,
  CELL_VERTICAL_ALIGNMENT_MAP,
  resolveHorizontalAlignment,
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
  const { rowHeight, rowHeightStyle } = tableMetadata || {};

  const verticalAlignment =
    metadata?.verticalAlignment ??
    (rowHeight === ROW_HEIGHT.small
      ? CELL_VERTICAL_ALIGNMENT.center
      : CELL_VERTICAL_ALIGNMENT.start);

  const verticalAlignClass = CELL_VERTICAL_ALIGNMENT_MAP[verticalAlignment];
  const horizontalAlignClass =
    CELL_HORIZONTAL_ALIGNMENT_CLASS_MAP[resolveHorizontalAlignment(metadata)];

  return (
    <div
      className={cn(
        "flex size-full overflow-hidden py-2 px-3",
        CELL_TEXT_CLASS_MAP[rowHeight ?? ROW_HEIGHT.small],
        stopClickPropagation && "cursor-auto",
        verticalAlignClass,
        horizontalAlignClass,
        className,
      )}
      style={rowHeightStyle}
      data-cell-wrapper={dataCellWrapper}
      {...(stopClickPropagation && {
        // Only clicks on the cell itself: a dialog or menu the cell opens is portaled elsewhere but its clicks
        // still pass through here, and stopping them keeps them from the document, where popovers inside that
        // dialog listen for outside clicks to close.
        onClick: (event) => {
          if (event.currentTarget.contains(event.target as Node)) {
            event.stopPropagation();
          }
        },
      })}
    >
      {children}
    </div>
  );
};

export default CellWrapper;
