import React from "react";
import { ColumnMeta, TableMeta } from "@tanstack/react-table";
import { CELL_VERTICAL_ALIGNMENT_MAP } from "@/constants/shared";
import {
  CELL_VERTICAL_ALIGNMENT,
  COLUMN_TYPE,
  ROW_HEIGHT,
} from "@/types/shared";
import { cn } from "@/lib/utils";

type CellWrapperProps = {
  children?: React.ReactNode;
  metadata?: ColumnMeta<unknown, unknown>;
  tableMetadata?: TableMeta<unknown>;
  className?: string;
  dataCellWrapper?: boolean;
};

const ALIGN_END_TYPES = [COLUMN_TYPE.number, COLUMN_TYPE.cost];

const CellWrapper: React.FunctionComponent<CellWrapperProps> = ({
  children,
  metadata,
  tableMetadata,
  className,
  dataCellWrapper = true,
}) => {
  const { type } = metadata || {};
  const { rowHeight, rowHeightStyle } = tableMetadata || {};

  const verticalAlignment =
    metadata?.verticalAlignment ??
    (rowHeight === ROW_HEIGHT.small
      ? CELL_VERTICAL_ALIGNMENT.center
      : CELL_VERTICAL_ALIGNMENT.start);

  const verticalAlignClass = CELL_VERTICAL_ALIGNMENT_MAP[verticalAlignment];
  const horizontalAlignClass = ALIGN_END_TYPES.includes(type!)
    ? "justify-end"
    : "justify-start";

  return (
    <div
      className={cn(
        "flex size-full py-2 px-3",
        verticalAlignClass,
        horizontalAlignClass,
        className,
      )}
      style={rowHeightStyle}
      data-cell-wrapper={dataCellWrapper}
    >
      {children}
    </div>
  );
};

export default CellWrapper;
