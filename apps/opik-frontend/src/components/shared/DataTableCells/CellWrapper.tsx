import React from "react";
import { ColumnMeta, TableMeta } from "@tanstack/react-table";
import { CELL_VERTICAL_ALIGNMENT_MAP } from "@/constants/shared";
import { CELL_VERTICAL_ALIGNMENT } from "@/types/shared";
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
  const { verticalAlignment = CELL_VERTICAL_ALIGNMENT.center } = metadata || {};
  const { rowHeightClass } = tableMetadata || {};

  const alignClass = CELL_VERTICAL_ALIGNMENT_MAP[verticalAlignment];

  return (
    <div
      className={cn(
        "flex size-full p-2",
        rowHeightClass,
        alignClass,
        className,
      )}
    >
      {children}
    </div>
  );
};

export default CellWrapper;
