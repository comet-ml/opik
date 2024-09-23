import React from "react";
import { ColumnMeta, TableMeta } from "@tanstack/react-table";
import { CELL_VERTICAL_ALIGNMENT_MAP } from "@/constants/shared";
import { CELL_VERTICAL_ALIGNMENT, COLUMN_TYPE } from "@/types/shared";
import { cn } from "@/lib/utils";

type CellWrapperProps = {
  children?: React.ReactNode;
  metadata?: ColumnMeta<unknown, unknown>;
  tableMetadata?: TableMeta<unknown>;
  className?: string;
};

const CellWrapper: React.FunctionComponent<CellWrapperProps> = ({
  children,
  metadata,
  tableMetadata,
  className,
}) => {
  const { verticalAlignment = CELL_VERTICAL_ALIGNMENT.center, type } =
    metadata || {};
  const { rowHeightClass } = tableMetadata || {};

  const verticalAlignClass = CELL_VERTICAL_ALIGNMENT_MAP[verticalAlignment];
  const horizontalAlignClass =
    type === COLUMN_TYPE.number ? "justify-end" : "justify-start";

  return (
    <div
      className={cn(
        "flex size-full p-2",
        rowHeightClass,
        verticalAlignClass,
        horizontalAlignClass,
        className,
      )}
    >
      {children}
    </div>
  );
};

export default CellWrapper;
